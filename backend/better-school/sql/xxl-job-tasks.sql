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


-- 商品资金孤儿扫描（ADR-0016 两段式资金链路的收敛入口，票 B）：无状态窗口重扫——
--   schedule_conf             = '0/30 * * * * ?'    30s 周期
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

-- 支付回调任务扫描（ADR-0017 域内最终一致任务）：扫到点的 PENDING 行，进程内重放商户回调
-- （支付单 PAID、冻结与任务行同事务落库，行在 ⇒ 钱已冻；回调幂等）；
-- 失败按 1/2/4/8/16 分钟退避，重试耗尽置死信。与 outboxScan 同口径 0/30。
INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '支付回调任务收敛（PENDING 重放/死信）', NOW(), NOW(), 'better-school', '',
       'CRON', '0/30 * * * * ?', 'DO_NOTHING', 'FIRST',
       'productPayTaskScan', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'productPayTaskScan');

-- 发帖返利领取任务收敛扫描（ADR-0017 域内最终一致任务）：扫到点的 PENDING 行，进程内重放领取
-- （领取按 ref 幂等）；失败按 1/2/4/8/16 分钟退避，重试耗尽置死信。与 outboxScan 同口径 0/30。
INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '发帖返利领取任务收敛（PENDING 重放/死信）', NOW(), NOW(), 'better-school', '',
       'CRON', '0/30 * * * * ?', 'DO_NOTHING', 'FIRST',
       'postRebateClaimScan', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'postRebateClaimScan');

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
       'drawCacheWarmup', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'drawCacheWarmup');

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

-- 抽奖库存全量对账轮（draw-recon-round 并入 master）——每日 03:00 低峰兜底：四条不变量
-- （B1/B3/正向配对/反向配对）全量轧账，差异进差错表状态机
-- （PENDING → CONFIRMED → AUTO_FIXED/RESOLVED_MANUAL/FALSE_ALARM）。
-- 与孤儿收敛 0/30 高频轮互补：逐笔链路收敛单笔残留，本任务回答「账到底平不平」。
-- 轮次号 = 当日 yyyy-MM-dd（job 层生成），同日重跑幂等。
INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '抽奖库存全量对账轮（每日 03:00 差异轧账进差错表）', NOW(), NOW(), 'better-school', '',
       'CRON', '0 0 3 * * ?', 'DO_NOTHING', 'FIRST',
       'drawReconScan', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'drawReconScan');

-- ─────────────────────────────── 退役清理（幂等，随票 07 合库收敛） ───────────────────────────────
-- marketing-executor 执行器组退役：合并后单执行器 better-school-executor 承接全部任务，
-- 旧组及其名下任务行（含合并前 marketing 侧的 outboxScan 行）一并清掉。
DELETE FROM xxl_job_info
WHERE job_group IN (SELECT id FROM (SELECT id FROM xxl_job_group WHERE app_name = 'marketing-executor') t);
DELETE FROM xxl_job_group WHERE app_name = 'marketing-executor';

-- 已下线 handler 的旧注册行（合并前 marketing seed 的清理口径平移，重灌不会再生）：
-- 桶过期清零改读路径懒判（pointsExpireScan / drawCountBucketExpireScan）、每秒库存合并换轨（inventoryMerge）
DELETE FROM xxl_job_info WHERE executor_handler IN ('pointsExpireScan', 'drawCountBucketExpireScan', 'inventoryMerge');
