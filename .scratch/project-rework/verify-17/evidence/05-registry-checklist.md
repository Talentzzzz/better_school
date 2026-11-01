# 票 17 编排收口核对表（注册面清零）

> 证据来源：`backend/scripts/dev-stack.ps1` 的 `xxl-seed` / `mq-prune` / `nacos-prune` 三个动作（均在 `up` 里按序执行，幂等可重复）。
> 原始清单：[`broker-topics-before.txt`](../broker-topics-before.txt) / [`broker-topics-after.txt`](../broker-topics-after.txt) / [`broker-consumer-groups-after.txt`](../broker-consumer-groups-after.txt)（在 verify-17/ 根，不在 evidence/ 下）

## 1. 端口收口（dev）

| 服务 | HTTP | Dubbo tri | xxl 执行器 | 库 |
| --- | --- | --- | --- | --- |
| better-school（单体） | 8080 | 50051 | 9999（appname `better-school-executor`） | `better_school` |
| marketing（points + raffle + inventory + drawcount） | 8081 | 50052 | 9998（`marketing-executor`） | `bs_marketing` |
| timeout-center | 8083 | 50054 | 9996（`timeout-center-executor`） | `bs_timeout` |

- **已释放**：8082 / 50053 / 9997（票 07 随两个营销工程合并释放）；`run-app.sh` 的 svc 键、`front/vite.config.ts` 的代理都在 8081。
- 票面把 9999 / 9998 / 9996 称作「actuator」有误：本仓**未引入** `spring-boot-starter-actuator`，也没有 `management.*` 配置；这三个是 **xxl-job 执行器端口**（`application.yml` 的 `xxl.job.executor.port`）。健康检查因此走各服务的匿名 HTTP 端点（见 `dev-stack.ps1` 头注释表）。
- 残留（非本票范围）：`backend/docs/api/README.md:5` 标题仍写「营销服务（8081/8082）」→ 归票 18 文档收口。

## 2. xxl-job 注册收口（`xxl_job.xxl_job_info` / `xxl_job_group`）

**清理前**（17 条任务 / 4 个执行器组，实测）：

| 组 | handler | 判定 |
| --- | --- | --- |
| better-school-executor | dailyCreditJob | ✂ 积分切桶后删除（票 10） |
| better-school-executor | delayOutboxScan | ✂ `delay_outbox_message` 随票 05 下线 |
| better-school-executor | wantToBuyTimeoutScan | ✂ 求购随票 03 下线 |
| marketing-asset-executor | outboxScan / pointsExpireScan | ✂ 执行器组随票 06 收敛为 `marketing-executor` |
| marketing-activity-executor | lotteryDeductMerge | ✂ 旧 lottery 库存合并，票 12/13 换成 `inventoryMerge` |
| marketing-activity-executor | lotteryDrawCountExpireScan | ✂ 换成 `drawCountBucketExpireScan`（票 10/13） |
| marketing-activity-executor | lotteryOutboxScan | ✂ 合并期兼容别名，票 13 删除 |
| marketing-activity-executor | referralOutboxScan | ✂ 拉新整域随票 09 删除 |

**清理后**（14 条，与 java 侧 `@XxlJob` 注解值逐条相等）：

| 组 | handler |
| --- | --- |
| better-school-executor | errandTimeoutScan / outboxScan / postReviewTimeoutScan(0) / productTimeoutScan / searchIndexRebuildJob(0) / timeoutRegisterOutboxScan / tradeExpireScan |
| marketing-executor | drawCountBucketExpireScan / inventoryBucketWarmup(0) / inventoryMerge / outboxScan / pointsExpireScan / raffleCacheWarmup |
| timeout-center-executor | timeoutTaskScanJob |

（`(0)` = `trigger_status=0`，脚本刻意不自动触发：全量重建 / 表预热 / 24h 提醒。）

废弃执行器组 `marketing-asset-executor` / `marketing-activity-executor` 的行与组一并删除。

## 3. RocketMQ topic / 消费组收口（开发 broker，cluster `DefaultCluster`）

**已删（6 个 topic + 6 个消费组 + 各自的 %RETRY%）**：

| 名 | 判定 |
| --- | --- |
| `bs-agent-review` / `bs-agent-review-group` | agent 审核整条链路（票 02） |
| `bs-want-paid` / `bs-want-paid-group` | 求购下单（票 03） |
| `bs-errand-grouped` / `bs-errand-grouped-group` | 多人单成团（票 04） |
| `bs-errand-member-changed` / `bs-errand-member-changed-group` | 多人单成员变更（票 04） |
| `bs-errand-timeout` / `bs-errand-timeout-group` | 跑腿超时改由 xxl `errandTimeoutScan` 扫描；**code-review 补录**——首轮清理漏掉，代码零引用但 broker 有残留 |
| `bs-errand-auto-complete` / `bs-errand-auto-complete-group` | 自动收货改由内部逻辑；**code-review 补录**（同上） |

补录两项已同步进 `dev-stack.ps1` 的 `$StaleMqNames` 并重跑 `mq-prune` 验证；`Constants.java` 里对应的两个死 topic 常量（`ERRAND_TIMEOUT_TOPIC` / `ERRAND_AUTO_COMPLETE_TOPIC`）一并删除，编译通过。

**referral / 旧 lottery_\* 的判定行**（票面点名，但 broker 上无可清理项）：`broker-topics-before.txt` 里不存在
任何 `referral*` / 旧 `lottery*` topic 或消费组——RocketMQ 的 topic/组都是首次生产/消费才建，这两个域在删除前
从未到过生产步，属于「从未注册」而非「废弃」，清零口径天然满足。

**保留（对账基数 = 代码侧 16 个在用消费组：14 个 `@RocketMQMessageListener` 字面量 + marketing 侧 2 个常量
`bs-points-lottery-drawn-group` / `bs-raffle-shipping-group`）**。清理后 broker 上实存 **15 个**：

`bs-user-register-group`、`bs-search-sync-group`、`bs-product-pay-timeout-group`、`bs-product-count-group`、
`bs-post-count-group`、`bs-monolith-points-changed-group`、`bs-monolith-lottery-drawn-group`、
`bs-errand-pay-timeout-group`、`bs-errand-paid-group`、`bs-comment-review-group`、`bs-chat-recall-group`、
`bs-chat-read-group`、`bs-chat-push-group`、`bs-points-lottery-drawn-group`、`bs-raffle-shipping-group`。

（差集 1 个 = `bs-notification-broadcast-group`：代码在用但尚无消费，broker 上未建。冒烟期间 `bs-lottery-drawn`
topic 与抽奖三个消费组已被首次生产/消费建出，故上表 15 个全部与代码逐条对应。）

> 唯一不属本仓业务的历史 topic 是 `BenchmarkTest`（RocketMQ 压测遗留的空 topic），保留不动。

**topic 侧对账**：清理后业务 topic **13 个**（见 `broker-topics-after.txt`），全部能在代码里找到生产/消费归属；
`bs-notification-broadcast` 尚无生产故未建。

## 4. Nacos 配置收口

删掉**已删除工程**留下的 10 条配置（`dev` 2 条 / `test` 8 条）：

| 实例 | dataId | group | 判定 |
| --- | --- | --- | --- |
| dev / test | `com.betterschool.asset.api.IPointsExchangeService`（+ `:::provider:marketing-asset`） | mapping / dubbo | `marketing-asset` 工程票 06/07 已删 |
| test | `com.betterschool.points.api.IPointsExchangeService`（+ `:::provider:points`） | mapping / dubbo | 旧 `points` 独立工程已并入 marketing |
| test | `com.betterschool.timeout.api.task.ITimeoutTaskCallback::stub-bs-{a,b}:provider:timeout-center` | dubbo | 旧测试桩遗留 |
| test | `probe-*.yml` ×2 | DEFAULT_GROUP | 集成测试探针遗留 |

保留的 `better-school-shared.yml`（jwt.secret / jwt.expire-days）在两个实例都在，内容一致——
三个服务的 `spring.config.import` 依赖它，缺了任何一个都起不来。
`com.betterschool.marketing.api.points.*` 与 `com.betterschool.timeout.*` 是在用项，前缀不匹配清理规则，未触碰。
