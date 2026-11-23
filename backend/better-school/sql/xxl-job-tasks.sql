-- better-school xxl-job 自动配置：执行器 + 任务清单（单体唯一 seed，monolith-restructure 票 07
-- 把合并前 marketing/sql/xxl-job-tasks.sql 的任务注册并入本文件；marketing-executor 组随之退役）
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

-- 帖子待审超时提醒：seed 幽灵行（monolith-restructure 票 07 核对：全工程已无该 @XxlJob handler）——
-- 不再注册，幂等清理存量库里已注册的旧行，否则调度仍会派给一个已删的 handler、日志刷「找不到执行器任务」
DELETE FROM xxl_job_info WHERE executor_handler = 'postReviewTimeoutScan';

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

-- 商品资金孤儿扫描（ADR-0016 两段式资金链路的收敛入口，票 B）：与跑腿同口径的窗口重扫——
--   schedule_conf             = '0/30 * * * * ?'    30s 周期（照 errandFundOrphanScan 口径）
--   executor_route_strategy   = 'FIRST'             单点执行即可：扫描是幂等重放，无需分片
--   executor_block_strategy   = 'SERIAL_EXECUTION'  tick 重叠时串行排队（孤儿是异常路径，常态 0 行）
--   executor_fail_retry_count = 0                   调度层不重试（周期重扫即重试）
INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '商品资金孤儿扫描（补解冻）', NOW(), NOW(), 'better-school', '',
       'CRON', '0/30 * * * * ?', 'DO_NOTHING', 'FIRST',
       'productFundOrphanScan', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'productFundOrphanScan');

-- ─────────────────────────────────────────────────────────────────────────────
-- 营销域任务（monolith-restructure 票 07 自 marketing/sql/xxl-job-tasks.sql 并入本文件）：
-- 执行器随单体归一为 better-school-executor；outboxScan 合并前两侧各注册一条，合并后
-- 全工程只剩一份 OutboxMessageSender（bs-infrastructure outbox 包，单 @XxlJob("outboxScan")），
-- marketing 侧那条不再注册（destination 区分消息来源），存量行由下方退役清理段收敛。
-- cron/路由/阻塞口径照合并前 marketing seed 原值平移（0/30 周期、FIRST 单点、SERIAL 排队）。
-- ─────────────────────────────────────────────────────────────────────────────

-- 购买单补偿扫描（ADR-0016 三段式：CREATED 单收敛到 DONE，扣分结果未知由扫描全量重放；
-- 积分不足单即置 DEAD、原 token 重放自愈）
INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '购买单补偿扫描（CREATED 单收敛/死信）', NOW(), NOW(), 'better-school', '',
       'CRON', '0/30 * * * * ?', 'DO_NOTHING', 'FIRST',
       'drawCountPurchaseScan', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'drawCountPurchaseScan');

-- 孤儿预扣对账扫描——「预扣在、抽奖记录不在、超过 10 分钟」的孤儿预扣收敛：
-- 决策可重放以原幂等键补落库（不重复扣次数、预扣撞唯一键按已扣回放，决策漂移接受）；
-- 不可重放（活动已结束/下线）库存回补 + 次数退还。重试口径与购买单补偿同（0/30、抢占、1/2/4/8/16 退避、死信）。
INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '孤儿预扣对账扫描（补落库或回补+退还）', NOW(), NOW(), 'better-school', '',
       'CRON', '0/30 * * * * ?', 'DO_NOTHING', 'FIRST',
       'orphanPrereserveScan', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'orphanPrereserveScan');

-- 抽奖决策表预热（票 11/13 补登口径平移）：参数 = activityId；配置变更后手动重跑，幂等（先删后写）
INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '抽奖决策表预热（参数 = activityId；配置变更后手动重跑，幂等）', NOW(), NOW(), 'better-school', '',
       'CRON', '0 0 2 * * ?', 'DO_NOTHING', 'FIRST',
       'raffleCacheWarmup', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'raffleCacheWarmup');

-- 积分桶合并（票 07 补登：handler 早就在 trigger/points/job/PointsBucketCompactionJob，此前漏了 seed 行）——
-- 碎片桶收敛：同用户的有效小额桶并成一桶（过期时间取合并集合最大值、旧桶归零留审计），
-- SUM(remain) 合并前后守恒、余额不变。cron 取 handler 注释建议值：每 10 分钟一次（合并不紧急、与每分钟扫描错峰）
INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '积分桶合并（碎片桶收敛，余额守恒）', NOW(), NOW(), 'better-school', '',
       'CRON', '0 0/10 * * * ?', 'DO_NOTHING', 'FIRST',
       'pointsBucketCompaction', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'pointsBucketCompaction');

-- ─────────────────────────────── 退役清理（幂等，随票 07 合库收敛） ───────────────────────────────
-- marketing-executor 执行器组退役：合并后单执行器 better-school-executor 承接全部任务，
-- 旧组及其名下任务行（含合并前 marketing 侧的 outboxScan 行）一并清掉。
DELETE FROM xxl_job_info
WHERE job_group IN (SELECT id FROM (SELECT id FROM xxl_job_group WHERE app_name = 'marketing-executor') t);
DELETE FROM xxl_job_group WHERE app_name = 'marketing-executor';

-- 已下线 handler 的旧注册行（合并前 marketing seed 的清理口径平移，重灌不会再生）：
-- 桶过期清零改读路径懒判（pointsExpireScan / drawCountBucketExpireScan）、每秒库存合并换轨（inventoryMerge）
DELETE FROM xxl_job_info WHERE executor_handler IN ('pointsExpireScan', 'drawCountBucketExpireScan', 'inventoryMerge');
