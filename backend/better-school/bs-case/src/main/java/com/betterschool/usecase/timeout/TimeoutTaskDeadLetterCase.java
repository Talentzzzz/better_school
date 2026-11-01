package com.betterschool.usecase.timeout;

import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.domain.timeout.model.valobj.DeadLetterPageVO;
import com.betterschool.domain.timeout.model.valobj.DeadLetterResendOutcomeVO;
import com.betterschool.domain.timeout.model.valobj.TaskKeyVO;
import com.betterschool.domain.timeout.service.deadletter.ITimeoutTaskDeadLetterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 死信管理用例（内嵌化票 06）：翻页、逐条重发、逐条取消。
 * <p>
 * 编排很薄——死信动作是**一条行上的条件 UPDATE**，没有跨行/跨域的一致性要守，
 * 所以批量重发就是顺序逐条调（不扇虚拟线程）：这是管理面的人工动作、量级是「管理员勾了几条」，
 * 为它开执行器只会把并发 DB 需求引到池上（单体池 10 与用户请求共用）而换不来任何东西。
 * 逐条的结果收进双清单回报。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeoutTaskDeadLetterCase {

    private final ITimeoutTaskDeadLetterService timeoutTaskDeadLetterService;

    /**
     * 翻一页死信（{@code updated_at} 倒序，最近进死信的在前）。
     *
     * @param page     页码，调用方（HTTP 边界）已归一到 ≥1
     * @param pageSize 每页行数，调用方已归一到 [1, 上限]
     */
    public DeadLetterPageVO pageDeadLetters(int page, int pageSize) {
        long total = timeoutTaskDeadLetterService.countDeadLetters();
        List<TimeoutTaskEntity> tasks =
                timeoutTaskDeadLetterService.listDeadLetters((page - 1) * pageSize, pageSize);
        return new DeadLetterPageVO(total, tasks);
    }

    /**
     * 批量重发（含「单条」= 单元素列表）：逐条 FAILED → RETRYING 并回报双清单。
     * <p>
     * 失败项不是异常：该键在这几秒里被别人重发过、或键写错了。逐项回报比整包失败有用
     * ——成功的那几条确实已经重开了（返回后由下一个扫描周期重投，重发不新开重试通路）。
     */
    public DeadLetterResendOutcomeVO resendDeadLetters(List<TaskKeyVO> keys) {
        DeadLetterResendOutcomeVO outcome = new DeadLetterResendOutcomeVO();
        for (TaskKeyVO key : keys) {
            if (timeoutTaskDeadLetterService.resendDeadLetter(key.getTaskType(), key.getBizId())) {
                outcome.success(key);
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

    /** 取消一条死信（FAILED → CANCELED）；返回 false = 该键当前不是 FAILED，无副作用 */
    public boolean cancelDeadLetter(String taskType, String bizId) {
        return timeoutTaskDeadLetterService.cancelDeadLetter(taskType, bizId);
    }
}
