package com.betterschool.marketing.domain.raffle.adapter.port.inventory;

/**
 * 库存域**兑换确认**端口（票 14 新增，库存域 {@code IInventoryQuotaService.confirmIssued} 的抽奖域视图）：
 * 把实物营销配额的悬挂预扣登记为「待转正式占用」——库存侧落一条 {@code type = 2} 明细 + 一条合并消息，
 * {@code waiting_qty → issued_qty} 的转移由库存域的合并异步完成（票 11 起不再同步转移）。
 * <p>
 * 为什么是端口的第二个方法而不是塞进 {@link IInventoryDeductPort}：预扣与确认是发放的两段，
 * 一个在抽奖主链路、一个在发货闭环，调用方与时机都不同；同域跨边界的两个动作放在同一个端口上也说得通，
 * 但那样会逼主链路认识「确认」这个它永远不用的概念。域服务互不注入（CLAUDE.md §1），故走端口。
 * <p>
 * 透传边界：端口只给 {@code invId} / {@code recordId} / 数量——桶号、业务类型这些库存列由库存侧自己补，
 * 抽奖域不认识库存的 VO 与表列。
 * <p>
 * 幂等边界：库存侧按 {@code (type = 2, biz_type, biz_id)} 唯一键去重——同一条记录重复确认按成功回放，
 * 不重复登记、不重复发消息，所以调用方不需要自己判「这一步做没做过」。
 */
public interface IQuotaConfirmPort {

    /**
     * 登记 quantity 份兑换确认（库存侧异步把 waiting 转成 issued）。
     *
     * @param recordId 抽奖记录 id（= {@code draw_record.record_id}）——从来不是独立兑换单号
     * @throws com.betterschool.marketing.types.common.AppException 配额不存在，或这笔记录没有预扣结转行
     */
    void confirmIssued(Long invId, Long recordId, long quantity);
}
