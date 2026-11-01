package com.betterschool.marketing.domain.inventory.model.valobj;

import com.betterschool.marketing.types.inventory.InventorySegmentVO;

/**
 * 一次预扣请求：把 {@code (segment, invId, quantity, bizType, bizId)} 五个散参收成一个值对象
 * （spec §7.1），让「主档 + 兜底」两次预扣可以并排传参、也让段可以不同（实物配额 → 虚拟权益）。
 *
 * @param bizType {@code InventoryBizTypes} 的取值（抽奖域不认识库存列，由库存侧端口实现填）
 * @param bizId   业务 ID（抽奖 = {@code draw_record.record_id}）：明细唯一键的去重锚点
 */
public record InventoryDeductRequestVO(InventorySegmentVO segment,
                                       long invId,
                                       long quantity,
                                       String bizType,
                                       long bizId) {
}
