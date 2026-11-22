package com.betterschool.marketing.trigger.raffle.job;

import com.betterschool.marketing.domain.raffle.service.recon.IRaffleDrawReconService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 抽奖库存全量对账轮（spec：每日 03:00 低峰调度）：逐笔孤儿收敛之上的总量兜底——
 * 四条不变量（B1 台账守恒 / B3 预扣守恒 / 正向配对 / 反向配对）每天真跑一遍，
 * 差异进差错表状态机（轧账登记 → 二次核对 → 平账分流），详见
 * {@link IRaffleDrawReconService}。轮次号取当日 {@code yyyy-MM-dd}（同日重跑幂等）。
 * <p>
 * 只转调域扫描入口（第二缝 = 触发层只测一条断言的先例）；不调 {@code XxlJobHelper}——
 * handler 正常返回即成功（与 {@link OrphanPrereserveScanJob} 同口径），
 * 触发层可在无 xxl 上下文的环境直调。cron 03:00 由 seed SQL 配置，不在代码里。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DrawReconScanJob {

    private final IRaffleDrawReconService raffleDrawReconService;

    @XxlJob("drawReconScan")
    public void execute() {
        int handled = raffleDrawReconService.scan(LocalDate.now().toString());
        if (handled > 0) {
            log.info("xxl-job: 全量对账轮处理差错 {} 条", handled);
        }
    }
}
