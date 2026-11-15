package com.betterschool.marketing.domain.inventory.model.valobj;

/**
 * 预扣（{@code submitAndWait(InventoryDeductRequestVO)}）的出口——<b>三条业务出口 + 三种引擎态</b>，
 * 全部由同一个返回值表达，调用方一次 {@code switch} 决定「继续 / 切兜底 / 抛可重试 / 同步降级」。
 *
 * <h2>三条业务出口（票 05 §5，本枚举的前三个）</h2>
 * <table border="1">
 *   <caption>业务出口与调用方动作</caption>
 *   <tr><th>出口</th><th>含义</th><th>调用方动作</th></tr>
 *   <tr><td>{@link #DEDUCTED}</td><td>已提交（{@code COMMIT} 之后才说成功），账已动</td><td>继续</td></tr>
 *   <tr><td>{@link #INSUFFICIENT}</td><td>确定没扣，台账一行没动</td><td>改发兜底档</td></tr>
 *   <tr><td>{@link #TIMEOUT_UNKNOWN}</td><td>还在缓冲 / 落库中，<b>大概率会扣</b></td>
 *       <td><b>不切兜底、不落库、抛可重试</b>：切兜底会扣两份库存发两个奖</td></tr>
 * </table>
 *
 * <h2>三种引擎态（spec §2.6：合并器只说「没接住」和「失败了」，不说 {@code REJECTED}）</h2>
 * <ul>
 *   <li>{@link #BUFFER_FULL} / {@link #CLOSED}：件<b>从未入队</b> ⇒ 调用方线程同步扣一批（组大小 1，
 *       <b>复用同一个落库方法</b>）；抽奖不该因为「合并器忙」就随机丢奖，所以这两态不返回给用户；</li>
 *   <li>{@link #FAILED}：落库抛异常 / 漏回填 / 停机排空被显式回填 ⇒ 事务已回滚（或从未开始），
 *       调用方按可重试处理，凭 {@code bizId} 重试（唯一键兜底，不会双扣）。</li>
 * </ul>
 *
 * <p><b>{@code TIMEOUT_UNKNOWN} 与 {@code FAILED} 不能合并成一个出口</b>：前者的事可能已经发生，
 * 后者的事确定没发生，而预扣是「用户付钱的那一下」，主链路必须当场决定切不切兜底（票 07 §6）。
 * 兑换确认没有这个需要——它后面有 MQ 重投、幂等键兜住，两个态在那边合并成一个可重试出口。
 */
public enum DeductResult {

    /** 成功：一组一事务已提交，台账已减、明细已落 */
    DEDUCTED,

    /** 业务失败：余量不足（含「探一次再切」里没被切到的那几笔），台账未动 */
    INSUFFICIENT,

    /** 等超时：件仍在缓冲或正在落库，之后大概率仍会扣 ⇒ 对外说「处理中」 */
    TIMEOUT_UNKNOWN,

    /** 分片缓冲已满：件未入队 ⇒ 调用方线程同步扣一批（组大小 1） */
    BUFFER_FULL,

    /** 合并器已停机：件未入队 ⇒ 同上 */
    CLOSED,

    /** 落库失败 / 漏回填 / 排空回填：事务已回滚或从未开始 ⇒ 可重试 */
    FAILED
}
