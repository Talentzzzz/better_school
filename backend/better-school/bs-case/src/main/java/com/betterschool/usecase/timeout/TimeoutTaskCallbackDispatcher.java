package com.betterschool.usecase.timeout;

import com.betterschool.domain.timeout.adapter.port.callback.ITimeoutTaskCallbackPort;
import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.idle.IdleCase;
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
 * <b>幂等</b>：中心 at-least-once，同一 {@code (taskType, bizId)} 可能重投。各动作本身都是
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

    private final IdleCase idleCase;

    /**
     * {@code taskType → 到期动作}。{@code bizId} 是 UUID 字符串订单业务 id（直传，uuid-biz-id 票 05）。
     */
    @Override
    public void callback(TimeoutTaskEntity taskEntity) {
        switch (taskEntity.getTaskType()) {
            case Constants.PRODUCT_ORDER_PAY_TIMEOUT -> idleCase.handlePayTimeout(taskEntity.getBizId());
            // 业务侧不认识这个 taskType = 注册与消费的代码不一致（如改了 Constants 取值），
            // 按失败重投直到进死信，在管理面显式暴露，不静默吞掉
            default -> throw new IllegalStateException("未接入的 taskType：" + taskEntity.getTaskType());
        }
    }
}
