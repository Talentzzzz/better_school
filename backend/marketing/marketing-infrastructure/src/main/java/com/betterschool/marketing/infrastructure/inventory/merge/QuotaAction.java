package com.betterschool.marketing.infrastructure.inventory.merge;

/**
 * 库存侧的两类写动作：合并器只按它做<b>二次分组</b>（动作 × segment × {@code invId}），不解析语义。
 *
 * <p>两者的失败域不同，所以是两个落库方法、两个结果枚举，<b>不合成一条 SQL</b>（spec §4.5）：
 * <ul>
 *   <li>{@link #DEDUCT}：{@code surplus → waiting}（实物）/ {@code surplus → issued}（虚拟）；
 *       {@code surplus} 不足是<b>业务</b>（切兜底档），回 {@code INSUFFICIENT}；</li>
 *   <li>{@link #CLAIM}：{@code waiting → issued}（只有实物段）；{@code waiting} 不足是
 *       <b>不变量破坏</b>（要人看），整组回滚 + 抛。</li>
 * </ul>
 *
 * <p>包内可见：它只服务于本包的分组与哨兵，调用方看到的是 {@code submitAndWait} 的重载。
 */
enum QuotaAction {

    /** 预扣：明细 {@code INSERT IGNORE} + 台账一条 {@code UPDATE} */
    DEDUCT,

    /** 兑换确认：{@code type = 2} 明细逐行 {@code INSERT IGNORE} + {@code waiting → issued} 一条 {@code UPDATE} */
    CLAIM
}
