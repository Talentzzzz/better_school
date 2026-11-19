package com.betterschool.marketing.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-Job 执行器（形状照单体）。三个后端 app + 各自独立 appname / port / log-path 都必须错开，
 * 否则 admin 会把任务派给别的应用、或执行器互相顶掉端口。本地无调度中心时注册失败仅日志告警。
 * <p>
 * 票 13 起本执行器承载的 handler：outboxScan、raffleCacheWarmup
 * （种子与清单见 sql/xxl-job-tasks.sql）。票 06 起 inventoryBucketWarmup 随桶层删除；
 * 票 08 起 inventoryMergeBackstop 随旧合并机制删除；
 * 桶过期清零的两个 handler（pointsExpireScan / drawCountBucketExpireScan）已下线——过期是读路径
 * 懒判，清零只写不读（且谓词走不了索引前缀，每分钟全表扫 + 整表加锁），净负收益。
 * 注册行的停用与 DELETE 时序归切换包（票 09）。
 * 旧 lottery 域的三个 handler（lotteryDeductMerge / lotteryDrawCountExpireScan /
 * lotteryOutboxScan）随旧域代码删除，xxl_job_info 里已注册的旧行需在 admin 里停用/删除。
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
