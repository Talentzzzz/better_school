package com.betterschool.marketing.infrastructure.inventory.dao;

import com.betterschool.marketing.infrastructure.inventory.dao.po.VirtualInventoryQuotaPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.VirtualQuotaRowPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 虚拟权益池台账表（virtual_inventory_quota）Mapper：一表一 DAO（明细表见 IVirtualInventoryQuotaDetailDao）。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/inventory/VirtualInventoryQuotaMapper.xml。
 */
@Mapper
public interface IVirtualInventoryQuotaDao {

    // ---- 台账 ----

    /** 库存行（不限 state，state 校验由域服务做） */
    VirtualInventoryQuotaPO selectInventory(@Param("invId") String invId);

    /** 台账当前余量（预扣「探一次再切」的剩余量来源）；{@code -1 = 不限量} */
    Long selectSurplus(@Param("invId") String invId);

    /**
     * 台账扣减（一条 {@code UPDATE}）：{@code surplus → issued}，带余量守卫——
     * 返回 0 行 = 余量不够（{@code affected = 0}，调用方探一次再切）。{@code -1 = 不限量}不守卫、不动 surplus。
     * surplus 恰好扣空由 {@code open} 置 {@code exhausted}。
     */
    int deductLedger(@Param("invId") String invId, @Param("quantity") long quantity);

    // ---- 预热 ----

    List<VirtualInventoryQuotaPO> selectOpenInventories();

    // ---- 奖品目录 / 中奖记录读侧（票 13，语句自 RaffleMapper 按主表归属迁入） ----

    /** 积分面额：virtual_inventory_quota.value（仅 type=POINTS 行） */
    Integer selectPointsValueByInvId(@Param("invId") String invId);

    /** 奖品目录三段查之②：虚拟台账按 inv_id 集批量回查（pointsAmount/remaining/total 拼装源） */
    List<VirtualQuotaRowPO> selectVirtualQuotasByInvIds(@Param("invIds") List<String> invIds);

    /** 积分面额批量读：仅 type=POINTS 的虚拟台账行（invId → value） */
    List<VirtualQuotaRowPO> selectPointValuesByInvIds(@Param("invIds") List<String> invIds);
}
