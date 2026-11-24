package com.betterschool.infrastructure.timeout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 死信管理面（运维用）：列表翻页 + 逐条重发 + 逐条取消，直接供 trigger 的 ADMIN 控制器调用。
 * <p>
 * 死信 = 回调连续失败到上限（默认 16 次）进 {@code FAILED} 终态的行；<b>扫描永远排除终态</b>，
 * 所以死信只能从这里人工处理。三个写动作各自是单条语句、各自只打 FAILED 行，所以
 * 「并发两个管理员同时重发同一条」只有一次生效、「重发与取消抢同一条」也只是其中一个赢
 * ——不需要在这里加锁或先查后判。
 * <p>
 * 人工重发/取消打告警日志：这是「回调 16 次仍失败」之后的人工出口，值得在日志里留一笔
 * （谁动过死信、动了哪条），虽没有独立的操作流水表。
 * <p>
 * 批量重发就是顺序逐条调（不扇虚拟线程）：这是管理面的人工动作、量级是「管理员勾了几条」，
 * 为它开执行器只会把并发 DB 需求引到池上（单体池 10 与用户请求共用）而换不来任何东西。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeoutDeadLetterAdmin {

    private final TimeoutTaskStore timeoutTaskStore;

    /**
     * 翻一页死信（{@code updated_at} 倒序，最近进死信的在前）。
     *
     * @param page     页码（调用方已归一到 ≥1）
     * @param pageSize 每页行数（调用方已归一到 [1, 上限]）
     */
    public DeadLetterPage page(int page, int pageSize) {
        long total = timeoutTaskStore.countDeadLetters();
        List<TimeoutTaskEntity> tasks = timeoutTaskStore.selectDeadLetters((page - 1) * pageSize, pageSize);
        return new DeadLetterPage(total, tasks);
    }

    /**
     * 批量重发（含「单条」= 单元素列表）：逐条 FAILED → RETRYING 并回报双清单。
     * <p>
     * 失败项不是异常：该键在这几秒里被别人重发过、或键写错了。成功的那几条确实已经重开了
     * （返回后由下一个扫描周期重投，重发不新开重试通路）。
     */
    public DeadLetterResendOutcome resend(List<TaskKey> keys) {
        DeadLetterResendOutcome outcome = new DeadLetterResendOutcome();
        for (TaskKey key : keys) {
            boolean resent = timeoutTaskStore.resendDeadLetter(key.getTaskType(), key.getBizId());
            if (resent) {
                outcome.success(key);
                log.warn("[超时中心告警] 死信人工重发：taskType={}, bizId={}（retry_count 清零、立即重开一轮）",
                        key.getTaskType(), key.getBizId());
            } else {
                outcome.fail(key);
            }
        }
        if (!outcome.getFailList().isEmpty()) {
            log.warn("[超时中心告警] 死信重发部分失败：成功 {} 条、失败 {} 条（失败项当前不是 FAILED）",
                    outcome.getSuccessList().size(), outcome.getFailList().size());
        }
        return outcome;
    }

    /**
     * 取消一条死信（FAILED → CANCELED 终态）。
     *
     * @return true = 确有行被取消；false = 该键当前不是死信（无副作用）
     */
    public boolean cancel(String taskType, String bizId) {
        boolean canceled = timeoutTaskStore.cancelDeadLetter(taskType, bizId);
        if (canceled) {
            log.warn("[超时中心告警] 死信人工取消：taskType={}, bizId={}", taskType, bizId);
        }
        return canceled;
    }
}
