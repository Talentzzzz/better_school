# outbox 分两种：域内最终一致任务用域自己的表，`mq_outbox` 只承载跨进程投递

**为什么写这条**：发帖返利领取任务曾被塞进 `mq_outbox`，再由 `outboxScan` 发到 `bs-post-rebate-claim`、由同进程的 `PostRebateClaimConsumer` 消费。这条链路暴露了模型混淆：行的职责明明是「这条帖子的返利还没领」，却被当成一条 MQ 消息，绕了一圈 broker 又回到同一进程。同时 `state` 出现两种含义——同步路径的 `SENT` 表示「领取完成」（case 置位），重放路径的 `SENT` 表示「已交给 broker」（扫描 job 置位，业务可能还在重试）。重试次数与死信也分裂在 broker 与 `retry_count` 两处，互不可见。本 ADR 把「outbox」拆成两个明确的形状，并确定各自的落表规则。

## Decision

- **outbox 有两种，是两张不同的表**：
  1. **跨进程投递 outbox**（`mq_outbox`）：行的职责是把字节送到 broker 的 topic。消费方在**另一个进程 / 另一个服务**，中间必须有传输。全库唯一一份（monolith-restructure 票 04/07 的既有拍板不变）。
  2. **域内最终一致任务表**（域自己一张）：行的职责是「这个本地动作还没做完」。消费方就是**本进程**，收敛方式 = 域自己的扫描 job + **进程内**幂等重放，**不经过 broker**。
- **判据唯一**：动作的收敛方是否跨进程边界？
  - 跨进程 → `mq_outbox` + topic + 消费者（如 `bs-post-rebate-credit`：rebate → points/draw 入账）。
  - 同进程 → 域自己的任务表 + 域自己的扫描 job（如 `timeout_task`、`draw_count_purchase_order`、`draw_orphan_prereserve_order`、`post_rebate_claim_task`）。
- **域要 outbox 就自己建表**：一个域需要承载「域内收敛任务」时，新建自己的表（一个语义一条链路一张，或订单兼 outbox 一张），不要往 `mq_outbox` 的 `destination` 里塞一个本进程消费的伪 topic。
- **任务表的形状**（对齐 ADR-0016 的孤儿扫描）：业务幂等键（如 `uk_post_id`）+ 三态 `state`（待收敛 / 完成 / 死信）+ `retry_count` + `next_retry_at`（指数退避）+ `finish_time`；扫描 job cron 与 `outboxScan` 同口径（`0/30`），认领用条件 UPDATE，单行失败不掀翻整轮批次。
- **状态语义统一**：任务表的 `state` 只表示「动作做没做完」，不再有「已交给 broker」的中间含义；重试与死信在 DB 可见。

## Considered Options

| 方案 | 为什么否 |
|---|---|
| 保持现状（本进程消费者也走 MQ） | 多一跳 broker 换来的只有「通用 `outboxScan` 无业务依赖」；但 trigger → case 的依赖本来就存在（消费者就在调 case），不新增依赖方向。同时制造 `state` 双语义与两套并行重试 |
| 让 `outboxScan` 按 `destination` 分发到进程内 handler | 可行但把通用基础设施 job 变成业务分发器；收敛语义仍借住消息表。不如按域拆表、按链路给 job |
| 把所有 `mq_outbox` 按域拆开 | 跨进程投递表拆开没有收益（传输职责与域无关），且与既有「全库唯一一份」拍板冲突 |
| 给每条链路新建状态表 | 对「跨进程投递」是重复建设；对「域内收敛」则是正确形状——本 ADR 只在这个范围内新增表 |

## Consequences

**正向**

- 发帖返利领取链路去掉一个 topic、一个消费者、一个消费组、一次序列化往返；重放走进程内调用。
- 任务 `state` 单义：`DONE` 恒等于「领取动作有确定结果」，`retry_count` / 死信可直接用 SQL 观测。
- 与仓库既有收敛范式（ADR-0016 的「每条两段式链路一个收敛 job」）一致，不再有唯一一条 MQ 收敛链路。
- 域边界更清：域内动作的持久化归域自己，`mq_outbox` 只表达跨进程事实。

**负向（已接受）**

- 每个域多一张任务表、一个 job；表与 job 的 DDL / 调度需要随链路一起维护。
- `mq_outbox` 与域任务表两套相似但不同的状态机，读代码时要先看「收敛方在哪」。
- 历史 `mq_outbox` 里可能残留 `bs-post-rebate-claim` 的 PENDING 行；已无消费者，直接删除或标 SENT 即可。

## Migration

- **发帖返利领取任务（2026-11-27）**：新增 `post_rebate_claim_task`；post 仓储在发帖 / 提交草稿事务内插行（PENDING），请求线程同步领取后置 DONE；新增 `PostRebateClaimScanJob`（`@XxlJob("postRebateClaimScan")`）进程内重放。删除 `Constants.POST_REBATE_CLAIM_TOPIC`、`PostRebateClaimConsumer`、`PostRebateClaimTaskMessage`、`PostRebateTaskKeys.claimOf`。
- **商品支付收敛任务（2026-11-27）**：新增 `wallet_pay_task`（归钱包域）。`WalletRepository.freeze(..., PRODUCT_FROZEN)` 在**同一个冻结事务**里写 PRODUCT_FROZEN 流水 + 余额条件扣减 + 本行 PENDING——任务行就是冻结的 outbox，提交则「钱已冻 + 意图在」，回滚则一起消失，「行在 ⇒ 钱已冻」由事务保证，不跨仓储（ADR-0016 边界不破）。请求线程随后同步翻转订单并置 DONE（幂等：流水 uk / 条件 UPDATE）；新增 `ProductPayTaskScanJob`（`@XxlJob("productPayTaskScan")`）重放未收敛行，失败 1/2/4/8/16 分钟退避、5 次耗尽置死信。终态订单的悬置冻结仍由 `productFundOrphanScan` 解冻收敛。（语义后由 ADR-0018 调整为「支付回调 outbox」：落行改在 `WalletRepository.pay` 的支付事务内，回调失败才留 PENDING；表结构与 job 名不变。）
- 存量：`mq_outbox` 中 `destination = 'bs-post-rebate-claim'` 的 PENDING 行删除或标 SENT（无消费者，重发无意义）；存量已发布帖无需补任务——返利领取按 ref 幂等，补任务只会立即 DONE。
- 文档随改：`docs/db/outbox.md`、`docs/db/post.md`、`docs/db/README.md`、`backend/docs/marketing/api/points.md`、根 `sql.md`。

## References

- `docs/adr/0016-tx-boundary-is-repository.md`（跨域不开事务；每条两段式链路一个收敛 job，收敛动作 = 按原幂等键重放）
- `backend/docs/architecture/timeout.md`（同进程任务表 + 扫描 + 进程内分发的先例）
- `backend/better-school/docs/db/post.md`（`post_rebate_claim_task` 表形状与流程）
