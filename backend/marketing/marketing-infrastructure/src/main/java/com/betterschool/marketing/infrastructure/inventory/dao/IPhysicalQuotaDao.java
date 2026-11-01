package com.betterschool.marketing.infrastructure.inventory.dao;

import com.betterschool.marketing.infrastructure.inventory.dao.po.PhysicalMarketingQuotaDetailPO;
import com.betterschool.marketing.infrastructure.inventory.dao.po.PhysicalMarketingQuotaPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 实物营销配额 Mapper（physical_marketing_quota 三张表）。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/inventory/PhysicalQuotaMapper.xml。
 * 写侧语句（预扣落库 / 兑换确认）假定在仓储方法（{@code InventoryRepository}）的事务内调用。
 * <p>
 * 票 06 起预扣明细不再写 {@code bucket_id} 与 {@code state}（桶层已删；列保留到 D9 收口，
 * {@code state} 落列默认 0）。
 */
@Mapper
public interface IPhysicalQuotaDao {

    // ---- 台账 ----

    PhysicalMarketingQuotaPO selectQuota(@Param("invId") Long invId);

    /** 台账当前余量（预扣「探一次再切」的剩余量来源）；{@code -1 = 不限量} */
    Long selectSurplus(@Param("invId") Long invId);

    // ---- 预扣落库（一组一事务，票 06） ----

    /**
     * 批量 {@code INSERT IGNORE} 预扣明细（{@code type = 1}）：撞 {@code uk_biz_type} 的行静默跳过，
     * 返回实际新增行数。幂等裁决完全交给唯一键。
     */
    int batchInsertIgnoreDeductDetails(@Param("invId") long invId,
                                       @Param("details") List<PhysicalMarketingQuotaDetailPO> details);

    /**
     * 批内撞键回查：捞回已存在的预扣行（它们的量之前已经扣过），调用方据此从本次扣减量里剔除。
     * 不按 {@code inv_id} 过滤——唯一键是全局的 {@code (type, biz_type, biz_id)}，
     * 落在别的 inv 上的同业务行同样算「已落过」。
     */
    List<PhysicalMarketingQuotaDetailPO> listExistingDeductDetails(@Param("bizType") String bizType,
                                                                   @Param("bizIds") List<Long> bizIds);

    /**
     * 台账扣减（一条 {@code UPDATE}）：{@code surplus → waiting}，带余量守卫——
     * 返回 0 行 = 余量不够（{@code affected = 0}，调用方探一次再切）。{@code -1 = 不限量}不守卫、不动 surplus。
     * surplus 恰好扣空由 {@code open} 置 {@code exhausted}。
     */
    int deductLedger(@Param("invId") Long invId, @Param("quantity") long quantity);

    /** 业务幂等预检：这场业务（type + biz_type + biz_id）在本 inv 的明细是否已存在（命中 = 已落过） */
    boolean existsDeductDetail(@Param("invId") Long invId, @Param("type") int type,
                               @Param("bizType") String bizType, @Param("bizId") long bizId);

    // ---- 兑换确认（同步合并，票 07） ----

    /**
     * 逐行 {@code INSERT IGNORE} 确认明细（{@code type = 2}）：撞 {@code uk_biz_type} 静默跳过，
     * 返回 1 = 新增 / 0 = 已确认过。确认批天然小（一份实物出库），逐行插不批插——
     * 「哪几行是新增」没有歧义，转移量精确等于新增量（spec §4.5）。
     * 新代码不写 {@code state}（列默认 0）与 {@code bucket_id}（桶层已删）。
     */
    int insertIgnoreClaimDetail(PhysicalMarketingQuotaDetailPO detailPO);

    /** waiting_qty >= qty 守卫下的转移（确认组落库的台账步）：0 行 = 没有足够的悬挂预扣 = 不变量破坏 */
    int transferWaitingToIssued(@Param("invId") Long invId, @Param("quantity") long quantity);

    // ---- 预热 ----

    List<PhysicalMarketingQuotaPO> selectOpenQuotas();
}
