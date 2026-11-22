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

（完成后在此追加要点）
