package com.betterschool.trigger.job;

import com.betterschool.infrastructure.timeout.ITimeoutHandler;
import com.betterschool.infrastructure.timeout.TimeoutTaskEntity;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.idle.IdleCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 到点动作的装配点：按 {@code taskType} 把超时组件交回来的任务分派到各域既有动作。
 * <p>
 * <b>为什么在 trigger</b>：分发要调业务 case，而 infra 看不见 case 层、case 也看不见 infra；
 * trigger 同时依赖两边，是做这份装配的唯一位置（同 {@code FileController → FileStorage} 的先例）。
 * 组件侧只认 {@link ITimeoutHandler}，不认识任何 {@code taskType}——超时机制与业务动作的重合点就这一个类。
 * <p>
 * <b>幂等</b>：组件 at-least-once，同一 {@code (taskType, bizId)} 可能重投。各动作本身都是
 * 「读状态 → 条件 UPDATE」的状态机，未命中状态机（已支付/已取消/不存在）= 幂等成功且零副作用——
 * 所以这里不需要任何去重设施，重复回调直接走同一条路。
 * <p>
 * <b>逐项隔离</b>：一次调用只处理一条；异常直接抛给调用方（{@code TimeoutScanner} 逐项 catch），
 * 一项失败不拖垮同批其余项——组件按失败项退避重投，一条坏数据不该拖住整批。
 * <p>
 * <b>新接入方</b>：在业务域的 {@code Constants} 加 {@code taskType}，在业务仓储的事务方法里
 * {@code timeoutTaskStore.register(...)}，然后在本类加一条 case 分支即可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeoutActionRouter implements ITimeoutHandler {

    private final IdleCase idleCase;

    @Override
    public void fire(TimeoutTaskEntity taskEntity) {
        switch (taskEntity.getTaskType()) {
            case Constants.PRODUCT_ORDER_PAY_TIMEOUT -> idleCase.handlePayTimeout(taskEntity.getBizId());
            // 业务侧不认识这个 taskType = 注册与消费的代码不一致（如改了 Constants 取值），
            // 按失败重投直到进死信，在管理面显式暴露，不静默吞掉
            default -> throw new IllegalStateException("未接入的 taskType：" + taskEntity.getTaskType());
        }
    }
}
