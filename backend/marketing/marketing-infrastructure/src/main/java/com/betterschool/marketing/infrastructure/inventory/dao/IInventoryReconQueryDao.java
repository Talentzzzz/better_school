package com.betterschool.marketing.infrastructure.inventory.dao;

import com.betterschool.marketing.infrastructure.inventory.dao.po.InventoryLedgerConservationPO;
import com.betterschool.marketing.infrastructure.inventory.dao.po.InventoryPrereserveConservationPO;
import com.betterschool.marketing.infrastructure.inventory.dao.po.UnmatchedDeductDetailPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对账轮（drawReconScan）只读聚合 Mapper——奖品库存台账 / 明细侧的三条不变量查询。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/inventory/InventoryReconQueryMapper.xml。
 * 纯旁路：只读聚合，不写任何表、不进合并器；全部带数据窗口 {@code update_time < before}
 * （{@code before = now - 10min}，与孤儿种单阈值对齐）。
 */
@Mapper
public interface IInventoryReconQueryDao {

    /**
     * B1 台账守恒：按 {@code award_id} 一行返回台账三列与 Σ(flow_type = 0 分配转入) 两侧读数，
     * 差异判断在域侧（限量户比三列、不限量户 {@code surplus_qty = -1} 跳过 surplus 项）。
     */
    List<InventoryLedgerConservationPO> queryLedgerConservation(@Param("before") LocalDateTime before);

    /**
     * B3 预扣守恒：按 {@code award_id} 一行返回台账 {@code waiting + issued} 与
     * Σ(flow_type = 1 预扣明细) 两侧读数（守恒判据 B3，出处见 mapper XML 注释）。
     */
    List<InventoryPrereserveConservationPO> queryPrereserveConservation(@Param("before") LocalDateTime before);

    /**
     * 正向配对：窗口内预扣明细（{@code bizType} 由端口填 LOTTERY_DRAW）的 {@code biz_id}
     * 反查 {@code draw_record} 与孤儿对账单都未命中 → 漏结候选行清单（返回行即差异）。
     */
    List<UnmatchedDeductDetailPO> listUnmatchedDeductDetails(@Param("before") LocalDateTime before,
                                                             @Param("bizType") String bizType);
}
