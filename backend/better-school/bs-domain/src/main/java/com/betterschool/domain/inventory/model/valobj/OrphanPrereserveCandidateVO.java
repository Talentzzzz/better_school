package com.betterschool.domain.inventory.model.valobj;

/**
 * 孤儿预扣候选（对账扫描出参）：一行「预扣在、抽奖记录不在」的预扣明细事实
 * （{@code flow_type = 1} + {@code biz_type = LOTTERY_DRAW} 且 {@code biz_id} 在
 * {@code draw_record} 无对应行、超过补偿阈值）。
 * <p>
 * 只带对账收敛需要的最小三 元组——账户类型不进快照，回补时由台账行现读
 * （PHYSICAL 走 {@code waiting → surplus}，VIRTUAL 走 {@code issued → surplus}）。
 *
 * @param awardId  奖品位 id（= 台账行）
 * @param bizId    抽奖记录 id（= {@code draw_record.record_id}，孤儿判据的反查锚点）
 * @param quantity 本行预扣份数（抽奖恒 1）
 */
public record OrphanPrereserveCandidateVO(String awardId, String bizId, long quantity) {
}
