package com.betterschool.domain.idle.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 商品资金孤儿扫描单轮汇总（ADR-0016 两段式的收敛 job 账目，写进 xxl 调度日志） */
@Data
@AllArgsConstructor
public class ProductFundOrphanScanSummaryVO {

    /** 单轮扫到的「平台有 PLATFORM_IN 无 PLATFORM_OUT」候选行数（含跳过的未终态行 / 订单缺失行） */
    private int platformInOrphanTotal;

    /** 订单 COMPLETED 补结算（平台出账 + 卖家入账）执行数：流水 uk 幂等吸收重复 */
    private int settleDone;

    /** 订单 CANCELLED 补退款（平台出账 + 买家入账）执行数：流水 uk 幂等吸收重复 */
    private int refundDone;
}
