package com.betterschool.usecase.timeout;

import com.betterschool.domain.moderation.service.IReviewService;
import com.betterschool.domain.timeout.adapter.port.callback.ITimeoutTaskCallbackPort;
import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.errand.ErrandOrderCase;
import com.betterschool.usecase.trade.ProductOrderCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 回调端口的进程内实现（超时中心内嵌化票 04）：按 {@code taskType} 分发到各域既有的到期动作。
 * <p>
 * 它取代了独立中心时代的 Dubbo 回调边（中心按任务行上的应用名动态建引用打到业务进程）——
 * 中心与业务同进程后，这条边塌成一次方法调用，{@code spring.application.name} 作为 group 的来源、
 * 以及「应用改名静默失配」那条坑一并消失。
 * <p>
 * <b>为什么在用例层</b>：分发要调其它域的 case（跨域写组合按 {@code backend/CLAUDE.md §1} 一律在用例层
 * 编排），而 infrastructure 看不到 case 层——这是「端口由 infrastructure 实现」在本域的一处显式例外。
 * <p>
 * <b>幂等</b>：中心 at-least-once，同一 {@code (taskType, bizId)} 可能重投。四个动作本身都是
 * 「读状态 → 条件 UPDATE」的状态机，未命中状态机（已支付/已取消/不存在）= 幂等成功且零副作用——
 * 所以本层不需要任何去重设施，重复回调直接走同一条路。
 * <p>
 * <b>逐项隔离</b>：一次调用只处理一条；异常直接抛给调用方（扫描 case 逐项 catch），
 * 一项失败不拖垮同批其余项——中心按失败项退避重投，一条坏数据不该拖住整批。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeoutTaskCallbackDispatcher implements ITimeoutTaskCallbackPort {

    private final ProductOrderCase productOrderCase;
    private final ErrandOrderCase errandOrderCase;
    private final IReviewService reviewService;

    /**
     * {@code taskType → 到期动作}。{@code bizId} 的形状**按 taskType 各自解析**：
     * 支付超时是十进制 id，锁单/租约超时是复合键 {@code {id}:{seq}}（不能走 {@code Long.parseLong} 那条路）。
     */
    @Override
    public void callback(TimeoutTaskEntity task) {
        switch (task.getTaskType()) {
            case Constants.PRODUCT_ORDER_PAY_TIMEOUT -> productOrderCase.handlePayTimeout(parseNumericBizId(task));
            case Constants.ERRAND_PAY_TIMEOUT -> errandOrderCase.handlePayTimeout(parseNumericBizId(task));
            case Constants.ERRAND_LOCK_TIMEOUT -> {
                CompositeBizId lockBizId = parseCompositeBizId(task, "{orderId}:{lockSeq}");
                errandOrderCase.handleLockTimeout(lockBizId.id(), lockBizId.seq());
            }
            case Constants.REVIEW_LEASE_TIMEOUT -> {
                // 审核租约回收：轮次守卫条件 UPDATE，未命中（已裁决/已放回/已按新轮次重领）＝幂等成功零副作用
                CompositeBizId leaseBizId = parseCompositeBizId(task, "{taskId}:{claimSeq}");
                reviewService.handleLeaseTimeout(leaseBizId.id(), leaseBizId.seq());
            }
            // 业务侧不认识这个 taskType = 注册与消费的代码不一致（如改了 Constants 取值），
            // 按失败重投直到进死信，在管理面显式暴露，不静默吞掉
            default -> throw new IllegalStateException("未接入的 taskType：" + task.getTaskType());
        }
    }

    /** 坏的 bizId 是注册方的编程错误：抛出去按失败重投（16 次后进死信，管理面可查），不静默当成功吞掉 */
    private static long parseNumericBizId(TimeoutTaskEntity task) {
        try {
            return Long.parseLong(task.getBizId());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("业务引用 id 不是数字：" + task.getBizId(), e);
        }
    }

    /** 复合 bizId：{id}:{seq}（锁单超时 = orderId:lockSeq，审核租约超时 = taskId:claimSeq） */
    private record CompositeBizId(long id, int seq) {
    }

    /** 形状不对同样是编程错误：抛出去按失败重投，不静默当成功（与 {@link #parseNumericBizId} 同口径） */
    private static CompositeBizId parseCompositeBizId(TimeoutTaskEntity task, String shape) {
        String bizId = task.getBizId();
        int separator = bizId == null ? -1 : bizId.indexOf(':');
        if (separator <= 0 || separator == bizId.length() - 1) {
            throw new IllegalStateException("复合业务引用形状应为 " + shape + "：" + bizId);
        }
        try {
            return new CompositeBizId(Long.parseLong(bizId.substring(0, separator)),
                    Integer.parseInt(bizId.substring(separator + 1)));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("复合业务引用形状应为 " + shape + "：" + bizId, e);
        }
    }
}
