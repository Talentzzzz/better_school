package com.betterschool.domain.errand.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 跑腿资金孤儿扫描单轮汇总（ADR-0016 两段式的收敛 job 账目，写进 xxl 调度日志） */
@Data
@AllArgsConstructor
public class ErrandFundOrphanScanSummaryVO {

    /** 第一段扫到的「有 FROZEN 无 UNFROZEN」冻结孤儿行数（含跳过的未终态行） */
    private int frozenOrphanTotal;

    /** 第一段补解冻（订单已终态）执行数：流水 uk 幂等吸收重复 */
    private int unfrozenDone;

    /** 第二段近窗口终态订单里补接单方入账执行数（COMPLETED）：流水 uk 幂等吸收重复 */
    private int incomeDone;

    /** 第二段近窗口终态订单里补接单方信用分扣减执行数（TIMEOUT + 已开工违约）：流水 uk 幂等吸收重复 */
    private int creditDone;
}
