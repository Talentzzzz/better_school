package com.betterschool.domain.raffle.adapter.port.inventory;

/**
 * 库存域**兑换确认**端口（票 14 新增，库存域 {@code IInventoryQuotaService.confirmIssued} 的抽奖域视图）：
 * 把实物奖品库存账户的悬挂预扣登记为「待转正式占用」——库存侧落一条 {@code flow_type = 2} 明细，
 * {@code waiting_qty → issued_qty} 的转移由同步合并一组一事务完成（返回即已提交）。
 * <p>
 * 为什么是端口的第二个方法而不是塞进 {@link IInventoryDeductPort}：预扣与确认是发放的两段，
 * 一个在抽奖主链路、一个在发货闭环，调用方与时机都不同；同域跨边界的两个动作放在同一个端口上也说得通，
 * 但那样会逼主链路认识「确认」这个它永远不用的概念。域服务互不注入（CLAUDE.md §1），故走端口。
 * <p>
 * award-inventory 两层模型：{@code invId} 形参的取值来源是 {@code draw_record.inv_id} 快照——
 * 它记的就是奖品位 id（奖品库存账户维度，开放点①），参数名沿用 {@code invId} 只是快照列名。
 * <p>
 * 透传边界：端口只给 {@code invId}（= awardId）/ {@code recordId} / 数量——账户类型、业务类型这些
 * 库存列由库存侧自己补，抽奖域不认识库存的 VO 与表列。
 * <p>
 * 幂等边界：库存侧按 {@code (flow_type = 2, biz_type, biz_id)} 唯一键去重——同一条记录重复确认
 * 按成功回放，不重复登记、不重复转移，所以调用方不需要自己判「这一步做没做过」。
 */
public interface IQuotaConfirmPort {

    /**
     * 登记 quantity 份兑换确认（库存侧同步把 waiting 转成 issued）。
     *
     * @param invId    奖品库存账户维度（= 奖品位 id，取自 {@code draw_record.inv_id} 快照）；不限量档为 null
     * @param recordId 抽奖记录 id（= {@code draw_record.record_id}）——从来不是独立兑换单号
     * @throws com.betterschool.types.common.AppException 这笔记录没有预扣结转行或确认失败（可重试）
     */
    void confirmIssued(String invId, String recordId, long quantity);
}
