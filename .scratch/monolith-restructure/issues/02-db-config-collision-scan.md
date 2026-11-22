# 02 合库/配置冲突扫描

Type: research
Status: resolved
Blocked by: —

## Question

合库 + 单应用合并前的事实扫描，输出风险清单：

1. 两个 `sql/schema.sql`（better-school 与 marketing）的表名对比：是否有同名表、同名不同结构；
2. marketing 是否使用 ShardingSphere（对比 bs-app 的 `sharding.yaml`）；两侧 `application.yml`/bootstrap 配置键冲突面（server.port、mybatis 扫描路径、rocketmq nameserver/group、xxl-job executor 配置、redis db 等）；
3. 两个 `xxl-job-tasks.sql` 的任务名对比（是否撞名、cron 汇总）；
4. RocketMQ topic / consumer group / tag 命名对比；
5. 两侧 controller 路径前缀清单（`@RequestMapping` 等），确认单应用后 HTTP 路径无冲突（front 的 vite proxy 现状已知：`/api/points`、`/api/lottery` 分流 marketing，其余 `/api` 到 bs）。

发现全文写入 `research/02-db-config.md`，本票 Resolution 只留指针与要点。

## Answer

完整报告：[research/02-db-config.md](../research/02-db-config.md)。要点：

1. **同名表仅 1 张**：`outbox_message`，marketing 侧是多 `delay_level`/`delay_ms` 两列的超集；其余表零交集（bs 41 张非分表 + timeout_task_00..15；marketing 23 张）。init 脚本只含库名，DDL 全在 schema.sql。
2. **Sharding**：bs 主 datasource 坐在 ShardingSphereDriver 上，仅分 `timeout_task`（16 分表，hint 强制路由），其余 41 张走 `!SINGLE` 透明单表规则；marketing 无任何 sharding。合库后 marketing 表在 SS 下走单表规则零改动。
3. **配置必撞键 8 组**：server.port（8080/8081）、app name、datasource、rocketmq.producer.group、dubbo name/port（50051/50052）、xxl-job appname/port/log-path。结构性要点：mybatis mapper-locations 必须改 `**` 递归（两域 OutboxMapper 同名 XML 分子目录）。两侧共享同一 Nacos dataId / xxl-job admin / Redis db0 / RocketMQ nameserver。
4. **xxl-job 撞名 1 个**：`outboxScan`（合并留一条）。另发现 2 处 seed↔代码不一致：bs 的 `postReviewTimeoutScan`（seed 有、代码无 handler）、marketing 的 `pointsBucketCompaction`（代码有、seed 无）——07 票执行时顺手核对。
5. **HTTP 前缀交集为 0**，单应用无路由冲突；且 marketing 有 2 个前缀（`/api/marketing/config`、`/api/marketing/inventory/allocate`）**不在** vite proxy 分流规则内（现状 dev 下打到 bs 侧 404），合并单端口后该缺口自然消除。
6. **RocketMQ topic 撞名 2 个**（`bs-lottery-drawn`、`bs-points-changed`）——是既定跨服务事件流（marketing 生产→bs 消费），非配置错误；consumerGroup 零撞名；两侧生产均走 outbox、无直发点。
