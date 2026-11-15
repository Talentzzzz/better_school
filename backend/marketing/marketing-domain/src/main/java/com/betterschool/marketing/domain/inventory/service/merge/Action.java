package com.betterschool.marketing.domain.inventory.service.merge;

/**
 * 奖品库存侧的两类写动作，合并器只按它做二次分组（动作 × type × awardId），不解析语义。
 * 两者失败域不同（DEDUCT 的 {@code surplus} 不足是业务，CLAIM 的 {@code waiting} 不足是
 * 不变量破坏），所以是两个落库方法、两个结果枚举，不合成一条 SQL。
 */
enum Action {

    /** 预扣：明细 {@code INSERT IGNORE} + 台账一条 {@code UPDATE} */
    DEDUCT,

    /** 兑换确认：{@code type = 2} 明细逐行 {@code INSERT IGNORE} + {@code waiting → issued} 一条 {@code UPDATE} */
    CLAIM
}
