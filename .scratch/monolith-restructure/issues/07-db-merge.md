# 07 合库与数据层配置合并

Type: task
Status: open
Blocked by: 05

## Question

执行（按 02 票扫描结果）：

1. `bs_marketing` 库的表并入主库：合并 `sql/schema.sql` 与 `init-dev.sql`/`init-test.sql`（有同名表按 04 票拍板处置）；marketing 库的 xxl-job 任务注册并入 `xxl-job-tasks.sql`；
2. 应用收敛为单数据源（删除 marketing 独立 datasource 配置），sharding 配置按需合并（若 marketing 用 sharding 则并入 `sharding.yaml`）；
3. RocketMQ topic/consumer-group 按 04 票拍板处置撞名。

完成标准：编译绿（配置/SQL 类改动）；独立 commit；不跑测试、不连库验证。跨域事务纪律（repo 边界、资金先行、唯一键幂等、孤儿扫描）不因合库改变，代码不改语义。

## Answer

（待执行后记录 commit hash 与要点）
