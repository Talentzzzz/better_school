package com.betterschool.marketing.infrastructure.inventory.dao;

import com.betterschool.marketing.infrastructure.inventory.dao.po.AwardInventoryDetailPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 奖品库存统一明细表（draw_strategy_award_inventory_detail）Mapper：一表一 DAO。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/inventory/AwardInventoryDetailMapper.xml。
 * 写侧语句假定在仓储方法（{@code InventoryRepository} / {@code InventoryQuotaFlushService}）的事务内调用。
 * <p>
 * 幂等生命线是 {@code uk_award_biz (award_id, biz_type, biz_id)}：预扣行（{@code flow_type = 1}）、
 * 确认行（{@code flow_type = 2}）与分配转入行（{@code flow_type = 0}，{@code biz_id = 分配单号}）共用它；
 * 撞键 = 这笔流转已落过，按成功回放。
 */
@Mapper
public interface IAwardInventoryDetailDao {

    /**
     * 批量 {@code INSERT IGNORE} 预扣明细（{@code flow_type = 1}）：撞 {@code uk_award_biz} 的行静默跳过，
     * 返回实际新增行数。幂等裁决完全交给唯一键。撞键快照回查见 {@link #listExistingDeductDetails}。
     */
    int batchInsertIgnoreDeductDetails(@Param("awardId") String awardId,
                                       @Param("details") List<AwardInventoryDetailPO> details);

    /**
     * 批内撞键回查：捞回本账户上已存在的预扣行（它们的量之前已经扣过），调用方据此从本次扣减量里剔除。
     * 按 {@code (award_id, biz_type, biz_id)} 查——唯一键就带 award_id 前缀，落在别的账户上的同业务行
     * 不算「已落过」（主档与兜底是两个账户，互不撞键）。
     */
    List<AwardInventoryDetailPO> listExistingDeductDetails(@Param("awardId") String awardId,
                                                           @Param("bizType") String bizType,
                                                           @Param("bizIds") List<String> bizIds);

    /** 业务幂等预检：这场业务（{@code flow_type = 1} + biz_type + biz_id）在本账户的明细是否已存在（命中 = 已落过） */
    boolean existsDeductDetail(@Param("awardId") String awardId, @Param("bizType") String bizType,
                               @Param("bizId") String bizId);

    /**
     * 逐行 {@code INSERT IGNORE} 确认明细（{@code flow_type = 2}）：撞 {@code uk_award_biz} 静默跳过，
     * 返回 1 = 新增 / 0 = 已确认过。确认批天然小（一份实物出库），逐行插不批插——
     * 「哪几行是新增」没有歧义，转移量精确等于新增量。
     */
    int insertIgnoreClaimDetail(AwardInventoryDetailPO detailPO);

    /**
     * 逐行 {@code INSERT IGNORE} 分配转入明细（{@code flow_type = 0}，{@code biz_id = 分配单号}）：
     * 撞 {@code uk_award_biz} 静默跳过，返回 1 = 新增 / 0 = 本单已分配过（按成功回放）。
     */
    int insertIgnoreAllocateDetail(AwardInventoryDetailPO detailPO);
}
