-- better-school xxl-job 自动配置：执行器 + 任务清单
-- 前提：先执行 xxl_job.sql（建库建表）；本脚本幂等，可重复执行
-- 执行：docker exec -i bs-mysql mysql -uroot -pbs123456 < better-school/sql/xxl-job-tasks.sql

USE xxl_job;

-- 0. 清理官方 tables_xxl_job.sql 自带示例数据（2 个 sample 执行器 + 3 条 demo 任务）
DELETE FROM xxl_job_info WHERE executor_handler IN ('demoJobHandler', 'ollamaJobHandler', 'difyWorkflowJobHandler');
DELETE FROM xxl_job_group WHERE app_name LIKE 'xxl-job-executor-sample%';

-- 1. 执行器：app_name 与执行器配置一致，address_type=0 自动注册（应用启动后自动更新地址）
INSERT INTO xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'better-school-executor', 'betterSchool', 0, NULL, NOW()   -- title 限 12 字符，'better-school' 13 字符超长
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_group WHERE app_name = 'better-school-executor');

SET @group_id = (SELECT id FROM xxl_job_group WHERE app_name = 'better-school-executor' LIMIT 1);

-- 2. 任务（按 executor_handler 判重，勿改 handler 名；trigger_status=1 直接启用）
INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, 'outbox PENDING 消息补发', NOW(), NOW(), 'better-school', '',
       'CRON', '0/30 * * * * ?', 'DO_NOTHING', 'FIRST',
       'outboxScan', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'outboxScan');

INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '搜索索引全量重建', NOW(), NOW(), 'better-school', '',
       'CRON', '0 30 3 * * ?', 'DO_NOTHING', 'FIRST',
       'searchIndexRebuildJob', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 0, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'searchIndexRebuildJob');

-- feed 索引全量重建：默认不自动触发（trigger_status=0）——同步是同事务直写、常态无漂移，
-- 重建是重活，仅在出现漂移信号（updateFeedIndexLikeCount 命中 0 行的 warn）时到 xxl-job-admin 手动执行
INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, 'feed 索引全量重建（手动）', NOW(), NOW(), 'better-school', '',
       'CRON', '0 40 3 * * ?', 'DO_NOTHING', 'FIRST',
       'feedIndexRebuildJob', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 0, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'feedIndexRebuildJob');

-- 根评论热评索引全量重建：同样默认不自动触发（trigger_status=0）——索引用点赞/取消赞同事务旁带维护、
-- 常态无漂移，重建是重活，仅在出现漂移信号（评论 updateFeedIndexLikeCount 命中 0 行的 warn、索引行被
-- 绕过直改、软删漏删索引行）时到 xxl-job-admin 手动执行
INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '根评论热评索引全量重建（手动）', NOW(), NOW(), 'better-school', '',
       'CRON', '0 45 3 * * ?', 'DO_NOTHING', 'FIRST',
       'commentFeedIndexRebuildJob', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 0, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'commentFeedIndexRebuildJob');

-- 市集两域 feed 索引全量重建：同样默认不自动触发（trigger_status=0）——同步是同事务直写、常态无漂移，
-- 重建是重活，仅在出现漂移信号（浏览旁路 updateFeedIndexViewCount 命中 0 行的 warn、计数窄表被绕过直改）
-- 时到 xxl-job-admin 手动执行；两域各一个 handler（数据源与索引列不同，见 MarketFeedIndexRebuildJob 类注释）
INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '商品 feed 索引全量重建（手动）', NOW(), NOW(), 'better-school', '',
       'CRON', '0 50 3 * * ?', 'DO_NOTHING', 'FIRST',
       'productFeedIndexRebuildJob', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 0, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'productFeedIndexRebuildJob');

INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '跑腿 feed 索引全量重建（手动）', NOW(), NOW(), 'better-school', '',
       'CRON', '0 55 3 * * ?', 'DO_NOTHING', 'FIRST',
       'errandFeedIndexRebuildJob', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 0, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'errandFeedIndexRebuildJob');

INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '交易到期下架+待审超时提醒扫描', NOW(), NOW(), 'better-school', '',
       'CRON', '0 * * * * ?', 'DO_NOTHING', 'FIRST',
       'tradeExpireScan', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'tradeExpireScan');

-- 帖子待审超时提醒：默认不自动触发（trigger_status=0），需要时在 xxl-job-admin 手动启用
INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '帖子待审超 24h 提醒扫描', NOW(), NOW(), 'better-school', '',
       'CRON', '0 * * * * ?', 'DO_NOTHING', 'FIRST',
       'postReviewTimeoutScan', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 0, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'postReviewTimeoutScan');

-- 超时任务扫描（内嵌化票 04；超时中心就这一个 job：扫描 = 重试 = 死信重发的入口）
-- 四个调度参数是防重防漏口径，别按默认值改：
--   schedule_conf             = '0/10 * * * * ?'    10s 周期（重试退避底数 30s，周期必须远小于它）
--   executor_route_strategy   = 'SHARDING_BROADCAST' 分片广播：每个实例都跑，靠 shardIndex/shardTotal 认领槽位
--   executor_block_strategy   = 'DISCARD_LATER'     tick 重叠时丢弃后到的那次（防漏靠下轮重扫）
--   executor_fail_retry_count = 0                   调度层不重试（周期重扫即重试）
--   misfire_strategy          = 'FIRE_ONCE_NOW'     错过就补一次
-- 注意：实例侧的并发上限不在这里，在应用配置 timeout.scan.parallelism（与连接池共用的余量口径）
INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '超时任务扫描（捞批+回调+结算）', NOW(), NOW(), 'better-school', '',
       'CRON', '0/10 * * * * ?', 'FIRE_ONCE_NOW', 'SHARDING_BROADCAST',
       'timeoutTaskScan', '', 'DISCARD_LATER', 0,
       0, 'BEAN', '', '', NOW(),
       '', 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'timeoutTaskScan');

-- 跑腿资金孤儿扫描（ADR-0016 两段式资金链路的收敛入口）：无状态窗口重扫——
--   schedule_conf             = '0/30 * * * * ?'    30s 周期（照 drawCountPurchaseScan 口径）
--   executor_route_strategy   = 'FIRST'             单点执行即可：扫描是幂等重放，无需分片
--   executor_block_strategy   = 'SERIAL_EXECUTION'  tick 重叠时串行排队（孤儿是异常路径，常态 0 行）
--   executor_fail_retry_count = 0                   调度层不重试（周期重扫即重试）
INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '跑腿资金孤儿扫描（补解冻/入账/扣分）', NOW(), NOW(), 'better-school', '',
       'CRON', '0/30 * * * * ?', 'DO_NOTHING', 'FIRST',
       'errandFundOrphanScan', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'errandFundOrphanScan');
