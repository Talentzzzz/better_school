# 购买次数链路最终一致性：购买单状态表 + 定时补偿（不引入分布式事务框架）

取代票 13 时代的「扣分与入账各自域内原子、共用购买单号幂等、靠客户端带 token 重试收敛」的口头约定。
那条约定有一个无人兜底的洞：客户端不重试（关页面 / 进程被杀），链路就永远停在
「积分已扣、次数未加」的中间态。本 ADR 把收敛责任从客户端挪回服务端：新增
`draw_count_purchase_order` 购买单表，**INSERT 购买单与扣积分在同一个物理事务**（同库同进程的红利），
「单在 ⇒ 分已扣」由此成立；次数入账天然幂等（`uk_user_reason_ref`），于是**任何一路重试都安全**：
客户端带 token 重试（Stripe 语义，重放返回原结果或顺势补齐）、xxl-job `drawCountPurchaseScan`
定时补偿（指数退避 5 次，耗尽进死信告警）、未来对账 job。宕机窗口从「永久不一致」缩到
「最多一个扫描周期」。

**为什么不用别的。** Seata / TCC / 2PC 过重（TCC 三套逻辑代码量翻三倍；本链路正向重试必收敛，
根本没有需要反向补偿的场景）；RocketMQ 事务消息本质是把本地消息表下沉进 Broker，但回查仍要求本地
状态表，省不掉任何表、只省扫描 job，而扫描 job 的基建（`outboxScan`）本仓库已有现成口径；
「两个域共用一个 MySQL 事务包住全部两步」做不到也不该做——入账必须留在扣分事务之外，
否则宕机窗口只是换个位置（提交与入账之间永远有一段不在任何事务里的缝）。

**表形态是有意选的。** 购买单是一张**业务单据表**而不是复用通用 `outbox_message`：这行的身份是
「订单」（用户可查、对账要用、Stripe 幂等入口），不是「要投递的信」。塞进通用 outbox 会四处难受：
发送状态语义（已发送 ≠ 入账完成）与业务状态机打架；业务列只能埋 JSON 没法建查询索引；
outbox 的清理生命周期会误删业务留痕。通用 outbox 继续只服务「广播事件」的链路
（如 `bs-lottery-drawn`），两件事分层，互不越界。

## Decision

- **购买单表 `draw_count_purchase_order`（订单兼 outbox）**：唯一键 `uk_purchase_order_id`
  （= `hash(userId, token)`，贯穿 `points_detail.ref_id` 与 `draw_count_bucket.ref_id`）；
  状态机只有三态 `DEDUCTED(0) → DONE(1)`，重试耗尽进 `DEAD(2)`——**无 FAILED**（正向重试必收敛，
  永不回滚），**无 PROCESSING**（并发抢占靠 `UPDATE ... WHERE state=0` 条件更新）。
- **组合事务在 raffle 侧 port 适配器**（`infrastructure.raffle.adapter.port.points.PointsDeductPort`）：
  一个 `TransactionTemplate`（REQUIRED）内先 `INSERT` 购买单再委托积分域 `deduct`
  （积分仓储同为 REQUIRED，自动并入）；积分不足或失败 → `setRollbackOnly` → 单与扣分一起消失。
  **不让 `PointsRepository` 写 raffle 的表**——points 域不该知道积分被花去买了什么。
- **三路重试收敛，全部踩同一幂等键**：① 客户端带同 token 重放：查单——`DONE` 返回原结果、
  中间态顺势补齐（Stripe 模式）；并发同 token 双击靠 uk 撞键转幂等推进；② xxl
  `drawCountPurchaseScan`（cron `0/30`，对齐 outboxScan 口径）：扫
  `state=DEDUCTED AND next_retry_at <= NOW()`，抢占式条件更新计数退避（1/2/4/8/16 分钟），
  重试只做「入账 + 置 DONE」，从不碰扣分；③ T+1 对账兜底另开票（见 Consequences）。
- **死信起步只日志 ERROR + 人工 SQL 重置**（`state=2` 置回 `0` 让扫描重领），
  告警通路等基建有了再接；`alarm_email` 现状为空，不为一条链路先建管理接口。
- **术语收敛**：本域统一用「购买」（purchase），`DrawCountReasonType.REDEEM` 改名 `PURCHASE`
  （存储值随枚举名落库，**存量库需删库重建**——本仓库改库表的标准口径）；
  `PointsReasonType.REDEEM_DRAW_COUNT` **不动**（它出现在 Dubbo 契约的 `valueOf` 入参上，改了断契约）。

## Considered Options

| 方案 | 为什么否 |
|---|---|
| 维持现状（客户端带 token 重试收敛） | 收敛押在客户端上：不重试就永久不一致；Stripe 自己都补了服务端重试到完成 |
| Seata / TCC / 2PC | TCC 代码量翻三倍；本链路无需要反向补偿的步骤；框架重量与收益完全不成比例 |
| RocketMQ 事务消息 | 回查要求本地可查事务状态，状态表省不掉；只省扫描 job 而扫描基建现成；排查链路多一跳 Broker |
| 复用通用 `outbox_message` | 见上：订单不是信封——状态语义、业务列索引、清理生命周期三处硬伤 |
| 积分域事务里顺带写购买单 | points 域反向依赖 raffle 的表与 DAO，域边界破在编译期 |
| 购买单先落（CREATED）再扣分再推进 | 多一个状态且「单在」只意味着「扣分结果未知」，补偿逻辑分叉；一事务两写更简单更强 |
| 本次一并落 T+1 对账 job | 一次 PR 闭环一条链路；对账等链路稳定后另开票 |

## Consequences

**正向**

- 中间态不再依赖客户端善意：宕机后最多一个扫描周期（30s）内收敛。
- 购买单同时是购买历史的查询面（`idx_user_time`）与对账源——通用 outbox 给不了这两样。
- 三路重试 + 双层幂等（单表 uk、桶 uk）+ 有界退避 + 死信，构成业界标准形状
  （本地事务 + 持久化待办 + 幂等下游 + 死信 + 对账），零框架依赖。

**负向（已接受）**

- 多一张表、一个 xxl 注册、一条扫描路径要维护；`draw_count_purchase_order` 的 DONE 行
  需要归档策略（留窗口期后清理），本期未建。
- 死信在生产上零自动信号（只日志 ERROR），处置靠人看日志——与 `alarm_email` 为空的现状一致，
  接监控时第一个该上报的就是死信条数。
- `REDEEM → PURCHASE` 存储值变更要求存量库删库重建；忘记重建的库会在读桶路径上
  `valueOf` 炸 `IllegalArgumentException`。

## References

- `backend/marketing/docs/architecture/marketing-services.md` §4（购买链路的现状口径，随本 ADR 更新）
- 业界口径：microservices.io Transactional Outbox / Idempotent Consumer；
  eBay Dan Pritchett《BASE: An Acid Alternative》(ACM Queue 2008)；
  Stripe Idempotent Requests（服务端重试到完成 + 幂等键 24h 窗口）
- 领域语言：根目录 `GLOSSARY.md`（购买 / 购买单 / 死信 / 对账）
