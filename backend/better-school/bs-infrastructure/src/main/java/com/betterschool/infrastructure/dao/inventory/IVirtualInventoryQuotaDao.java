package com.betterschool.infrastructure.dao.inventory;

import com.betterschool.infrastructure.dao.inventory.po.VirtualInventoryQuotaPO;
import com.betterschool.infrastructure.dao.raffle.po.VirtualQuotaRowPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 虚拟库存配额池台账表（virtual_inventory_quota）Mapper：一表一 DAO（award-inventory 两层模型，
 * 本表是**外界投放配额池**——只被「分配」转出 surplus，抽奖的预扣只打奖品库存统一台账，
 * {@code deductLedger} 等预扣类 SQL 已从调用面退役）。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/inventory/VirtualInventoryQuotaMapper.xml。
 */
@Mapper
public interface IVirtualInventoryQuotaDao {

    // ---- 台账 ----

    /** 库存行（不限 state，state 校验由域服务做） */
    VirtualInventoryQuotaPO selectInventory(@Param("invId") String invId);

    /**
     * 分配扣减（池侧守卫转出，{@code InventoryRepository#allocate} 的事务步）：
     * {@code state = 'open'} 且 {@code (surplus_qty < 0 OR surplus_qty >= N)} 守卫下转出——
     * 0 行 = 余量不足或池非 open（分配域服务已先行校验 open，这里守卫并发窗口）。
     * {@code -1 = 不限量}放行、不动 surplus（口径与旧 {@code deductLedger} 一致）。
     */
    int deductForAllocate(@Param("invId") String invId, @Param("quantity") long quantity);

    // ---- 预热 ----

    List<VirtualInventoryQuotaPO> selectOpenInventories();

    // ---- 奖品目录 / 中奖记录读侧（票 13，语句自 RaffleMapper 按主表归属迁入） ----

    /** 积分面额：virtual_inventory_quota.value（仅 type=POINTS 行；池是面额配置的真相所在） */
    Integer selectPointsValueByInvId(@Param("invId") String invId);

    /** 虚拟台账批量读（invId → type/value/surplus/issued，目录 pointsAmount 的面额配置拼装源） */
    List<VirtualQuotaRowPO> selectVirtualQuotasByInvIds(@Param("invIds") List<String> invIds);

    /** 积分面额批量读：仅 type=POINTS 的虚拟台账行（invId → value） */
    List<VirtualQuotaRowPO> selectPointValuesByInvIds(@Param("invIds") List<String> invIds);
}
