package com.betterschool.trigger.job;

import com.betterschool.usecase.errand.ErrandOrderCase;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 跑腿超时兜底扫描（XXL-Job 调度：cron 0 * * * * ?）：
 * MQ 延迟消息的主路径之外的第二道保险，扫描超时订单走幂等处理（与主路径并发无害）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrandTimeoutScanJob {

    private final ErrandOrderCase errandOrderCase;

    @XxlJob("errandTimeoutScan")
    public String scan(String param) {
        errandOrderCase.scanTimeouts();
        return "success";
    }
}
