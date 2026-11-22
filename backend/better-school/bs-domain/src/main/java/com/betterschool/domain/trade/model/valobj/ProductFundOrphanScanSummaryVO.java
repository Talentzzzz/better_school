package com.betterschool.domain.trade.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 商品资金孤儿扫描单轮汇总（ADR-0016 两段式的收敛 job 账目，写进 xxl 调度日志） */
@Data
@AllArgsConstructor
public class ProductFundOrphanScanSummaryVO {

    /** 第一段扫到的「有 PRODUCT_FROZEN 无 PRODUCT_UNFROZEN」冻结孤儿行数（含跳过的未终态行） */
    private int frozenOrphanTotal;

    /** 第一段补解冻（订单已终态）执行数：流水 uk 幂等吸收重复 */
    private int orphanUnfrozenDone;

    /** 第二段近窗口终态订单里补买家解冻退款执行数（已支付取消）：流水 uk 幂等吸收重复 */
    private int replayUnfrozenDone;
}
