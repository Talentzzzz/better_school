package com.betterschool.marketing.infrastructure.inventory.dao;

import com.betterschool.marketing.infrastructure.inventory.dao.po.PhysicalMarketingQuotaPO;
import com.betterschool.marketing.infrastructure.raffle.dao.po.PhysicalQuotaRowPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 实物营销配额台账表（physical_marketing_quota）Mapper：一表一 DAO（明细表见 IPhysicalMarketingQuotaDetailDao）。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/inventory/PhysicalQuotaMapper.xml。
 */
@Mapper
public interface IPhysicalQuotaDao {

    // ---- 台账 ----

    PhysicalMarketingQuotaPO selectQuota(@Param("invId") String invId);

    /** 台账当前余量（预扣「探一次再切」的剩余量来源）；{@code -1 = 不限量} */
    Long selectSurplus(@Param("invId") String invId);

    /**
     * 台账扣减（一条 {@code UPDATE}）：{@code surplus → waiting}，带余量守卫——
     * 返回 0 行 = 余量不够（{@code affected = 0}，调用方探一次再切）。{@code -1 = 不限量}不守卫、不动 surplus。
     * surplus 恰好扣空由 {@code open} 置 {@code exhausted}。
     */
    int deductLedger(@Param("invId") String invId, @Param("quantity") long quantity);

    /** waiting_qty >= qty 守卫下的转移（确认组落库的台账步）：0 行 = 没有足够的悬挂预扣 = 不变量破坏 */
    int transferWaitingToIssued(@Param("invId") String invId, @Param("quantity") long quantity);

    // ---- 预热 ----

    List<PhysicalMarketingQuotaPO> selectOpenQuotas();

    // ---- 奖品目录读侧（票 13，语句自 RaffleMapper 按主表归属迁入） ----

    /** 奖品目录三段查之③：实物台账按 inv_id 集批量回查（remaining/total 拼装源） */
    List<PhysicalQuotaRowPO> selectPhysicalQuotasByInvIds(@Param("invIds") List<String> invIds);
}
