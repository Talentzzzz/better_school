Status: ready-for-agent
Type: task
Blocked by: 03

# 05 xxl-job 触发器 + 调度注册

## 目标

新增每日 03:00 全量对账轮的触发层薄壳与 xxl-job 注册 SQL。

## 上下文指针

- spec：`.scratch/draw-recon-round/spec.md`（Implementation Decisions 调度节：照 `OrphanPrereserveScanJob` 口径——trigger 层薄壳、无 xxl 上下文可直调、按 award_id 分批）
- 探索报告：`.scratch/draw-recon-round/exploration.md` §1.2（薄壳模式全文：**不调 XxlJobHelper**、cron 不在代码里）、§4 落点表、§8（xxl 执行器 app_name `marketing-executor`）
- 依赖票：票 03 合入后读其 Answer 拿服务入口签名。

## 交付内容

1. `marketing-trigger/.../raffle/job/DrawReconScanJob.java`：照 `OrphanPrereserveScanJob` 逐行风格——`@XxlJob("drawReconScan")`、只转调域扫描入口、不调 `XxlJobHelper`、常量批大小、结果只 log.info。javadoc 声明薄壳纪律。
2. `marketing/sql/xxl-job-tasks.sql` 追加幂等 INSERT：handler `drawReconScan`、cron `0 0 3 * * ?`、app_name `marketing-executor`，照文件头注与 raffleCacheWarmup 每日任务行形状（WHERE NOT EXISTS 幂等）。

## 验收

- `mvn compile` 通过；不写测试、不跑测试。
- 触发层代码里不出现 cron、不出现 `XxlJobHelper`。

## Answer

实现者：t05（分支 `draw-recon-round-t05-xxl-job`）。完全镜像 `OrphanPrereserveScanJob` 薄壳模式。

### 新增/改动文件清单

| 文件 | 内容 |
| --- | --- |
| `marketing-trigger/.../raffle/job/DrawReconScanJob.java` | 新增：`@XxlJob("drawReconScan")` 薄壳，只转调 `IRaffleDrawReconService.scan(LocalDate.now().toString())`（轮次号 = 当日 `yyyy-MM-dd`，job 层生成）；不调 `XxlJobHelper`、代码里无 cron；处理数 > 0 时 log.info。javadoc 声明薄壳纪律与每日 03:00 低峰调度 |
| `marketing/sql/xxl-job-tasks.sql` | 追加幂等 INSERT（WHERE NOT EXISTS，判重键 executor_handler='drawReconScan' + job_group）：cron `0 0 3 * * ?`、app_name `marketing-executor`（经 @group_id）、trigger_status=1 直接启用，形状照 raffleCacheWarmup 每日任务行 |

### 关键口径

- 服务入口签名以域层代码为准：`IRaffleDrawReconService.scan(String roundNo)` 返回本轮处理差错数（二次核对确认数 + 复查证伪数 + DEAD 点名数），与票 03 Answer 一致。
- cron 由 seed SQL 配置（`0 0 3 * * ?`），触发层代码不出现 cron；`misfire_strategy=DO_NOTHING` 与既有行一致。
- 未写/未跑任何测试（spec Testing Decisions）。

### 编译结果

`mvn -q compile`（marketing 工程，JDK 21）通过，EXIT=0。
