package com.betterschool.marketing.trigger.raffle.job;

import com.betterschool.marketing.domain.raffle.service.orphan.IRaffleOrphanPrereserveService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 孤儿预扣对账扫描（spec User Stories 14–16）：扫「预扣在、抽奖记录不在、超过补偿阈值（10 分钟）」
 * 的孤儿预扣，两条收敛路径——决策可重放（活动仍有效）以原幂等键重放落库后半段（不重复扣次数、
 * 预扣撞唯一键按已扣回放，决策漂移接受）；不可重放（活动已结束 / 下线）库存回补 + 次数退还。
 * 重试口径照 {@link DrawCountPurchaseScanJob}：0/30 cron、条件更新抢占、指数退避
 * （1/2/4/8/16 分钟）、耗尽进死信（只 ERROR 日志 + 人工 SQL 重置，不建告警通路）。
 * <p>
 * 只转调域扫描入口（第二缝 = 触发层只测一条断言的先例）；不调 {@code XxlJobHelper}——
 * handler 正常返回即成功（与 better-school 的 {@code SearchIndexRebuildJob} 同口径），
 * 触发层可在无 xxl 上下文的环境直调。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanPrereserveScanJob {

    /** 单轮扫描上限：孤儿预扣是宕机残留，正常水位下每轮只有零星几行 */
    private static final int SCAN_LIMIT = 100;

    private final IRaffleOrphanPrereserveService raffleOrphanPrereserveService;

    @XxlJob("orphanPrereserveScan")
    public void execute() {
        int converged = raffleOrphanPrereserveService.reconcile(SCAN_LIMIT);
        if (converged > 0) {
            log.info("xxl-job: 孤儿预扣对账收敛 {} 笔", converged);
        }
    }
}
