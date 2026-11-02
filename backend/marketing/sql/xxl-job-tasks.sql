-- marketing xxl-job 自动配置：执行器 + 任务清单
-- 前提：先执行 xxl_job.sql（建库建表，见 backend/sql/xxl-job.sql）；本脚本幂等，可重复执行
-- 执行：docker exec -i bs-mysql mysql -uroot -pbs123456 < marketing/sql/xxl-job-tasks.sql
-- 营销 handler 首次落 SQL（原先手工在 admin 配、无仓内落点——service-restructure 票 03）。
-- 执行器 app_name 随票 06 收敛为 marketing-executor（application.yml 的 xxl.job.executor.appname 同步）；
-- 任务清单随票 07 把 marketing-activity 的 lottery handler 并入本文件（一份 seed 管一个工程）。
-- 两个 outbox 扫描 handler 并存的原因：合并后只剩一份 OutboxMessageSender，
-- 它同时注册 outboxScan / lotteryOutboxScan 两个 @XxlJob 名以兼容旧注册
-- （两域同名 outboxScan 的解法：lottery 侧改 lotteryOutboxScan，
-- 仅注解值不同，语义相同——都转调同一个扫描方法）。
-- 票 09 下线该域：对应 outbox 扫描名的注册语句与 handler 一并删除，
-- xxl_job_info 里已注册的旧行需在 admin 里停用/删除（本脚本幂等重灌不会再建出它）。
-- cron 口径：admin 库当时无可核对的现配置（xxl_job_info 无营销执行器行），按 spec §3.1-D 建议值落：
-- outbox 类 0/30（单体先例）、pointsExpireScan / lotteryDeductMerge / lotteryDrawCountExpireScan 每分钟。
-- author 口径：两工程原值（marketing-asset / marketing-activity）随工程合并统一取 'marketing'。

USE xxl_job;

-- 1. 执行器：app_name 与执行器配置一致，address_type=0 自动注册（应用启动后自动更新地址）
INSERT INTO xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'marketing-executor', 'mktAsset', 0, NULL, NOW()   -- title 限 12 字符
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_group WHERE app_name = 'marketing-executor');

SET @group_id = (SELECT id FROM xxl_job_group WHERE app_name = 'marketing-executor' LIMIT 1);

-- 2. 任务（按 executor_handler 判重，勿改 handler 名；trigger_status=1 直接启用）
INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, 'outbox PENDING 消息补发', NOW(), NOW(), 'marketing', '',
       'CRON', '0/30 * * * * ?', 'DO_NOTHING', 'FIRST',
       'outboxScan', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'outboxScan'
                  AND job_group = @group_id);

INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '积分桶到期懒过期兜底扫描', NOW(), NOW(), 'marketing', '',
       'CRON', '0 * * * * ?', 'DO_NOTHING', 'FIRST',
       'pointsExpireScan', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'pointsExpireScan'
                  AND job_group = @group_id);

INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '抽奖次数桶（新模型）到期懒过期兜底扫描', NOW(), NOW(), 'marketing', '',
       'CRON', '0 * * * * ?', 'DO_NOTHING', 'FIRST',
       'drawCountBucketExpireScan', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'drawCountBucketExpireScan'
                  AND job_group = @group_id);

-- 票 10（库存合并触发换轨）：每秒的 inventoryMerge 已删除。
-- 先幂等清理旧注册：否则调度仍会派给一个已删的 handler、日志刷「找不到执行器任务」
DELETE FROM xxl_job_info WHERE executor_handler = 'inventoryMerge' AND job_group = @group_id;

-- hot-row-merge-l2w256 票 09：inventoryMergeBackstop / inventoryBucketWarmup 不再注册——
-- 新代码没有这两个 handler（合并进进程、无桶可预热），seed 也不再造出它们。
-- 存量库的旧行处置：uuid-biz-id 票 08 起存量库退役（删库重建），reset-test-db / db-reset 重灌本 seed 即不再有这两行。

-- 票 11（决策侧预热）：参数 = activityId；配置变更后手动重跑，幂等（先删后写）
-- 票 13 补登：handler 早已存在（trigger/raffle/job/CacheWarmupJob），此前漏了 seed 行
INSERT INTO xxl_job_info
(job_group, job_desc, add_time, update_time, author, alarm_email,
 schedule_type, schedule_conf, misfire_strategy, executor_route_strategy,
 executor_handler, executor_param, executor_block_strategy, executor_timeout,
 executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime,
 child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT @group_id, '抽奖决策表预热（参数 = activityId；配置变更后手动重跑，幂等）', NOW(), NOW(), 'marketing', '',
       'CRON', '0 0 2 * * ?', 'DO_NOTHING', 'FIRST',
       'raffleCacheWarmup', '', 'SERIAL_EXECUTION', 0,
       0, 'BEAN', '', '', NOW(),
       '', 1, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM xxl_job_info WHERE executor_handler = 'raffleCacheWarmup'
                  AND job_group = @group_id);
