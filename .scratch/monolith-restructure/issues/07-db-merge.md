# 07 合库与数据层配置合并

Type: task
Status: done
Blocked by: 05

## Question

执行（按 02 票扫描结果）：

1. `bs_marketing` 库的表并入主库：合并 `sql/schema.sql` 与 `init-dev.sql`/`init-test.sql`（有同名表按 04 票拍板处置）；marketing 库的 xxl-job 任务注册并入 `xxl-job-tasks.sql`；
2. 应用收敛为单数据源（删除 marketing 独立 datasource 配置），sharding 配置按需合并（若 marketing 用 sharding 则并入 `sharding.yaml`）；
3. RocketMQ topic/consumer-group 按 04 票拍板处置撞名。

完成标准：编译绿（配置/SQL 类改动）；独立 commit；不跑测试、不连库验证。跨域事务纪律（repo 边界、资金先行、唯一键幂等、孤儿扫描）不因合库改变，代码不改语义。

## Answer

（commit `0e7c193`，2026-11-23，分支 chat-ws-layering-rollout）

- **条目 2（单数据源）在票 05 已先行完成**：application.yml / application-test.yml 只剩 SS 驱动壳一个 datasource，marketing 侧 datasource 配置随 application.yml 合并退役；marketing 无 sharding，22 张表在 `!SINGLE "*.*.*"` 下透明，sharding.yaml 零改动。
- **schema 合并**：marketing 22 张表（原 23 张减 outbox_message）+ 两行配置种子（points_validity_config 365 天 / draw_count_config 30 天）平移进 `better-school/sql/schema.sql` 尾段（带合库来源注释）；`outbox_message` 按 04 票拍板取 marketing 超集形状（+delay_level/delay_ms）就地替换，全文件恰好一份 DDL。合库后 79 张表 = bs 57（41 非分表 + timeout_task_00..15）+ marketing 22。
- **init 脚本**：`bs_marketing` / `bs_marketing_test` 库名退役，库名收敛为 `better_school` / `better_school_test`（bs 侧 init-dev/init-test 原样即够用）；`backend/sql/marketing/` 四件（init-dev/init-test/schema/xxl-job-tasks）删除。
- **xxl-job seed 并单**：`better-school/sql/xxl-job-tasks.sql` 成为唯一 seed——营销三任务平移（drawCountPurchaseScan / orphanPrereserveScan / raffleCacheWarmup，cron/路由/阻塞口径原值，author 归一 'better-school'）；outboxScan 只留单体一条（marketing 侧那条不再注册）。**两处 seed↔代码不一致顺手修**：`postReviewTimeoutScan` 幽灵行改幂等 DELETE；`pointsBucketCompaction`（handler 在、seed 缺）补登（cron 取 handler 注释建议值 0 0/10）。`marketing-executor` 组及其名下旧行（含旧 outboxScan 行）幂等清理退役；marketing seed 的下线 handler 清理段（pointsExpireScan/drawCountBucketExpireScan/inventoryMerge）口径平移。
- **脚本接线同步**：test-env.sh（去 apply_service_init/SERVICE_*_DBS/marketing 建库与重建）、dev-stack.ps1（Invoke-DbInit 单库、Invoke-XxlSeed 单 seed、头注释③④）、docker-compose.yml 头注释（手工建库命令、库名清单）。
- **条目 3（RocketMQ）处置 = 保留现状不改代码**：topic 撞名 2 个（bs-lottery-drawn / bs-points-changed）是既定跨服务事件流、consumerGroup 零撞名、producer group 已在票 05 归一——零配置冲突；链路进程内化退役是代码语义变更，超出本票「代码不改语义」边界，与 04 票第 1 条 trigger 归并去留联动，归后续 effort。
- **验证**：全 reactor `test-compile` 绿（未跑测试、未连库，按票面标准）；SQL 自检 79 表 / outbox 单份 / 3 段种子 / seed 14 INSERT + 6 DELETE。
- **遗留归票 08（docs 口径同步）**：backend/docs/db/README.md、architecture/marketing-services.md、testing.md、sql.md、backend/docs/marketing/db/* 仍述双库双 seed；dev-stack.ps1 的两服务起停段（$Services 仍含 backend/marketing 工程，05 起已失效）与测试基类注释（bs_marketing_test）待同步。
