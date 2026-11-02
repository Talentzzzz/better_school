package com.betterschool.marketing.infrastructure.inventory.dao;

import com.betterschool.marketing.infrastructure.inventory.dao.po.VirtualInventoryQuotaDetailPO;
import com.betterschool.marketing.infrastructure.inventory.dao.po.VirtualInventoryQuotaPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 虚拟权益池 Mapper（virtual_inventory_quota 三张表，一域段一 DAO）。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/inventory/VirtualInventoryQuotaMapper.xml。
 * <p>
 * 票 06 起预扣明细不再写 {@code bucket_id} 与 {@code state}（桶层已删；列保留到 D9 收口，
 * {@code state} 落列默认 0）。
 */
@Mapper
public interface IVirtualInventoryQuotaDao {

    // ---- 台账 ----

    /** 库存行（不限 state，state 校验由域服务做） */
    VirtualInventoryQuotaPO selectInventory(@Param("invId") String invId);

    /** 台账当前余量（预扣「探一次再切」的剩余量来源）；{@code -1 = 不限量} */
    Long selectSurplus(@Param("invId") String invId);

    // ---- 预扣落库（一组一事务，票 06） ----

    /**
     * 批量 {@code INSERT IGNORE} 预扣明细：撞 {@code uk_biz} 的行静默跳过，返回实际新增行数。
     * 幂等裁决完全交给唯一键。
     */
    int batchInsertIgnoreDeductDetails(@Param("invId") String invId,
                                       @Param("details") List<VirtualInventoryQuotaDetailPO> details);

    /**
     * 批内撞键回查：捞回已存在的预扣行（它们的量之前已经扣过），调用方据此从本次扣减量里剔除。
     * 不按 {@code inv_id} 过滤——唯一键是全局的 {@code (biz_type, biz_id)}，
     * 落在别的 inv 上的同业务行同样算「已落过」。
     */
    List<VirtualInventoryQuotaDetailPO> listExistingDeductDetails(@Param("bizType") String bizType,
                                                                  @Param("bizIds") List<String> bizIds);

    /**
     * 台账扣减（一条 {@code UPDATE}）：{@code surplus → issued}，带余量守卫——
     * 返回 0 行 = 余量不够（{@code affected = 0}，调用方探一次再切）。{@code -1 = 不限量}不守卫、不动 surplus。
     * surplus 恰好扣空由 {@code open} 置 {@code exhausted}。
     */
    int deductLedger(@Param("invId") String invId, @Param("quantity") long quantity);

    /** 业务幂等预检：这场业务（biz_type + biz_id）在本 inv 的明细是否已存在（命中 = 已落过） */
    boolean existsDeductDetail(@Param("invId") String invId, @Param("bizType") String bizType, @Param("bizId") String bizId);

    // ---- 预热 ----

    List<VirtualInventoryQuotaPO> selectOpenInventories();
}
