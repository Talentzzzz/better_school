package com.betterschool.infrastructure.timeout;

/**
 * 到点动作的 SPI：组件只管「哪一行到点了」，<b>到点该干什么由装配方给</b>。
 * <p>
 * 实现落在 {@code trigger/job/TimeoutActionRouter}——分发要调各业务域的 case，而 infra 看不见 case 层；
 * trigger 同时依赖 case 与 infra，天然是做这份装配的地方（同 {@code FileController → FileStorage} 的先例）。
 * 组件这一侧只认接口，不认识任何 {@code taskType}。
 * <p>
 * 契约：
 * <ul>
 *   <li><b>逐项</b>：一次调用只处理一条任务；批内隔离由 {@link TimeoutScanner} 负责，一项抛异常不拖垮同批其余项；</li>
 *   <li><b>at-least-once</b>：同一 {@code (taskType, bizId)} 可能被重复调用，<b>幂等责任在业务状态机</b>
 *       （未命中状态机 = 幂等成功，零副作用）；</li>
 *   <li><b>异常即失败</b>：未注册的 {@code taskType}、形状不对的 {@code bizId} 一律抛出去按失败重投
 *       （16 次后进 FAILED 死信、在管理面显式暴露），不静默吞掉。</li>
 * </ul>
 */
@FunctionalInterface
public interface ITimeoutHandler {

    /**
     * 对一条到期的超时任务执行业务侧到期动作。
     *
     * @throws RuntimeException 动作失败（含无法识别的 taskType / 坏 bizId）——调用方按失败重投入重试或死信
     */
    void fire(TimeoutTaskEntity timeoutTaskEntity);
}
