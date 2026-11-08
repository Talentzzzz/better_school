package com.betterschool.marketing.infrastructure.inventory.dao;

import com.betterschool.marketing.infrastructure.inventory.dao.po.PhysicalMarketingQuotaDetailPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 实物营销配额明细表（physical_marketing_quota_detail）Mapper：一表一 DAO。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/inventory/PhysicalMarketingQuotaDetailMapper.xml。
 * 写侧语句（预扣落库 / 兑换确认）假定在仓储方法（{@code InventoryRepository}）的事务内调用。
 * <p>
 * 票 06 起预扣明细不再写 {@code bucket_id} 与 {@code state}（桶层已删；列保留到 D9 收口，
 * {@code state} 落列默认 0）。
 */
@Mapper
public interface IPhysicalMarketingQuotaDetailDao {

    /**
     * 批量 {@code INSERT IGNORE} 预扣明细（{@code type = 1}）：撞 {@code uk_biz_type} 的行静默跳过，
     * 返回实际新增行数。幂等裁决完全交给唯一键。
     */
    int batchInsertIgnoreDeductDetails(@Param("invId") String invId,
                                       @Param("details") List<PhysicalMarketingQuotaDetailPO> details);

    /**
     * 批内撞键回查：捞回已存在的预扣行（它们的量之前已经扣过），调用方据此从本次扣减量里剔除。
     * 不按 {@code inv_id} 过滤——唯一键是全局的 {@code (type, biz_type, biz_id)}，
     * 落在别的 inv 上的同业务行同样算「已落过」。
     */
    List<PhysicalMarketingQuotaDetailPO> listExistingDeductDetails(@Param("bizType") String bizType,
                                                                   @Param("bizIds") List<String> bizIds);

    /** 业务幂等预检：这场业务（type + biz_type + biz_id）在本 inv 的明细是否已存在（命中 = 已落过） */
    boolean existsDeductDetail(@Param("invId") String invId, @Param("type") int type,
                               @Param("bizType") String bizType, @Param("bizId") String bizId);

    /**
     * 逐行 {@code INSERT IGNORE} 确认明细（{@code type = 2}）：撞 {@code uk_biz_type} 静默跳过，
     * 返回 1 = 新增 / 0 = 已确认过。确认批天然小（一份实物出库），逐行插不批插——
     * 「哪几行是新增」没有歧义，转移量精确等于新增量（spec §4.5）。
     * 新代码不写 {@code state}（列默认 0）与 {@code bucket_id}（桶层已删）。
     */
    int insertIgnoreClaimDetail(PhysicalMarketingQuotaDetailPO detailPO);
}
