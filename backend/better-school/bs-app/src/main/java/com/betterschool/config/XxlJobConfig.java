package com.betterschool.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-Job 执行器配置（CLAUDE.md config 约定：线程池/Redis/MQ/xxljob 等 Bean 装配）。
 * 本地无调度中心时执行器注册失败仅日志告警，不影响应用启动；调度中心随 Docker 编排联调部署
 */
@Configuration
public class XxlJobConfig {

    @Bean
    public XxlJobSpringExecutor xxlJobSpringExecutor(
            @Value("${xxl.job.admin.addresses}") String adminAddresses,
            @Value("${xxl.job.access-token}") String accessToken,
            @Value("${xxl.job.executor.appname}") String appname,
            @Value("${xxl.job.executor.port}") int port,
            @Value("${xxl.job.executor.log-path}") String logPath,
            @Value("${xxl.job.executor.log-retention-days}") int logRetentionDays) {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAccessToken(accessToken);
        executor.setAppname(appname);
        executor.setPort(port);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(logRetentionDays);
        return executor;
    }
}
