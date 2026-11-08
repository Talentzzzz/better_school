package com.betterschool.domain.timeout.adapter.port.callback;

import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;

/**
 * 回调端口：把「到点的超时任务」交回业务侧执行到期动作（超时中心内嵌化票 04）。
 * <p>
 * 独立中心时代这是一条 Dubbo 边（中心 → 业务方的回调契约接口，随内嵌化票 05 连同旧中心契约
 * jar 依赖一并删除）；内嵌化后
 * 中心与业务同进程，这条边塌成一次<b>进程内方法调用</b>：按 {@code taskType} 分发到各域的既有动作。
 * 端口留在域层是沿用原中心的形状（域层不认识对端实现），<b>实现落在用例层</b>
 * （{@code usecase.timeout.TimeoutTaskCallbackDispatcher}）而不是 infrastructure：
 * 分发要调其它域的 <b>case</b>（跨域写组合按 {@code backend/CLAUDE.md §1} 一律在用例层编排），
 * 而 infrastructure 看不到 case 层。这是 §3「端口由 infrastructure 实现」在本域的一处显式例外。
 * <p>
 * 契约（与独立中心一致）：
 * <ul>
 *   <li><b>逐项</b>：一次调用只处理一条任务；批内隔离由调用方（扫描 case）负责，一项抛异常不拖垮同批其余项；</li>
 *   <li><b>at-least-once</b>：同一 {@code (taskType, bizId)} 可能被重复调用，<b>幂等责任在业务状态机</b>
 *       （未命中状态机 = 幂等成功，零副作用）；</li>
 *   <li><b>异常即失败</b>：未注册的 {@code taskType}、形状不对的 {@code bizId} 一律抛出去按失败重投
 *       （16 次后进 FAILED 死信、在管理面显式暴露），不静默吞掉。</li>
 * </ul>
 */
public interface ITimeoutTaskCallbackPort {

    /**
     * 对一条到期的超时任务执行业务侧到期动作。
     *
     * @throws RuntimeException 动作失败（含无法识别的 taskType / 坏 bizId）——调用方按失败重投入重试或死信
     */
    void callback(TimeoutTaskEntity taskEntity);
}
