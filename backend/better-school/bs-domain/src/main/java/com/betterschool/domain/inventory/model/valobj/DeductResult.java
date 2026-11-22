package com.betterschool.domain.inventory.model.valobj;

/**
 * 预扣的<b>业务出口</b>：三条，一次 {@code switch} 决定「继续 / 切兜底 / 抛可重试」。
 *
 * <table border="1">
 *   <caption>出口与调用方动作</caption>
 *   <tr><th>出口</th><th>含义</th><th>调用方动作</th></tr>
 *   <tr><td>{@link #DEDUCTED}</td><td>已提交（{@code COMMIT} 之后才说成功），账已动</td><td>继续</td></tr>
 *   <tr><td>{@link #INSUFFICIENT}</td><td>确定没扣，台账一行没动</td><td>改发兜底档</td></tr>
 *   <tr><td>{@link #RETRY_LATER}</td><td>结果未知（还在处理中）或确定未落（事务已回滚）</td>
 *       <td><b>不切兜底、不落库、抛可重试</b>：切兜底会扣两份库存发两个奖</td></tr>
 * </table>
 *
 * <p><b>为什么只有三条</b>：缓冲满 / 停机 / 落库失败这些是<b>基础设施内部的事</b>
 * （合并、攒批、降级为同步落库都在仓储实现里，域不该知道这次扣减走没走合并器）。
 * 域只需要「扣到了 / 没扣（业务）/ 现在说不清（可重试）」三句话。
 *
 * <p>{@code RETRY_LATER} 内部还分两种事实（结果未知 vs 确定没落），但对外是同一个动作：
 * 凭 {@code bizId} 重放，明细唯一键兜底——落过的按成功回放、没落的补上，不会双扣。
 */
public enum DeductResult {

    /** 成功：事务已提交，台账已减、明细已落 */
    DEDUCTED,

    /** 业务失败：余量不足（含「探一次再切」里没被切到的那几笔），台账未动 */
    INSUFFICIENT,

    /** 拿不到确定结论：还在处理中，或事务已回滚 ⇒ 抛可重试，凭业务身份重放 */
    RETRY_LATER
}
