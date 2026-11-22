package com.betterschool.infrastructure.dao.inventory;

import com.betterschool.infrastructure.dao.inventory.po.PhysicalMarketingQuotaPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 实物营销配额池台账表（physical_marketing_quota）Mapper：一表一 DAO（award-inventory 两层模型，
 * 本表是**外界投放配额池**——只被「分配」转出 surplus，抽奖的预扣/确认只打奖品库存统一台账，
 * {@code deductLedger} 等预扣类 SQL 已从调用面退役）。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/inventory/PhysicalQuotaMapper.xml。
 */
@Mapper
public interface IPhysicalQuotaDao {

    // ---- 台账 ----

    PhysicalMarketingQuotaPO selectQuota(@Param("invId") String invId);

    /**
     * 分配扣减（池侧守卫转出，{@code InventoryRepository#allocate} 的事务步）：
     * {@code state = 'open'} 且 {@code (surplus_qty < 0 OR surplus_qty >= N)} 守卫下转出——
     * 0 行 = 余量不足或池非 open（分配域服务已先行校验 open，这里守卫并发窗口）。
     * {@code -1 = 不限量}放行、不动 surplus（口径与旧 {@code deductLedger} 一致）。
     */
    int deductForAllocate(@Param("invId") String invId, @Param("quantity") long quantity);

    // ---- 预热 ----

    List<PhysicalMarketingQuotaPO> selectOpenQuotas();
}
