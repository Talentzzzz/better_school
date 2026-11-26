Status: resolved

# 发帖返利：同步领取 + 异步入账（每日限一次）

术语遵循根目录 `CONTEXT.md` 的「行为返利」「积分桶」「抽奖次数桶」「幂等」；事务与仓储边界遵循 ADR-0016（事务落位在仓储）。

## Problem Statement

发帖用户发布成功后，前端只显示「发布成功」：返利实际由 MQ 消费者异步发放，请求线程既不知道这次有没有返利，也不知道返多少。运营给发帖返利加上「每天只能领一次」后，问题更严重——是否命中当天名额必须在请求线程里原子判定，否则提示必然失真：要么告诉用户有返利、实际没领到；要么告诉用户没有、实际已经发放。

## Solution

把发帖返利拆成两段：

- **同步领取（claim）**：发帖事务提交后，请求线程立刻在返利域原子地抢占「当天发帖返利」名额，快照积分/抽奖次数金额，并把结果随发帖响应返回；前端据此立刻 toast。
- **异步入账（credit）**：领取成功后的积分/抽奖次数入账继续走 outbox + MQ，积分桶/抽奖次数桶的唯一键吸收重复；入账失败由 broker 重试。

用户发完帖立刻看到四种明确结果之一：

- 「获得 X 积分 + Y 次抽奖」
- 「今日发帖返利已领过」
- 「返利发放中」
- 无开放规则时只显示「发布成功」

提示与实际发放使用同一份领取快照，不会出现「提示 10 分、到账 5 分」。

## User Stories

1. 作为发帖用户，我想发布成功后立刻看到这次获得的返利金额，以便不用去积分页确认是否到账
2. 作为发帖用户，我想同时看到积分和抽奖次数两种返利，以便知道这次发帖的全部收益
3. 作为发帖用户，我想今天已经领过发帖返利时看到「今日已领」提示，以便不会误以为系统漏发
4. 作为发帖用户，我想运营关闭返利规则时只看到「发布成功」，以便不会被不存在的返利误导
5. 作为发帖用户，我想返利入账失败或延迟时看到「发放中」而不是具体金额，以便提示不会说谎
6. 作为发帖用户，我想同一天发多条帖子只领到一次返利，以便规则符合运营配置
7. 作为发帖用户，我想并发或重复提交时也只领一次返利，以便网络抖动不会让我多领或少领
8. 作为草稿作者，我想提交草稿时获得与直接发布一致的返利提示，以便两条发布路径体验一致
9. 作为草稿作者，我想保存或编辑草稿不触发返利，以便草稿不会消耗当天名额
10. 作为发帖用户，我想提示里的金额与实际入账金额一致，以便我信任返利规则
11. 作为发帖用户，我想返利最终入账后积分桶和抽奖次数桶余额正确，以便我可以正常使用
12. 作为发帖用户，我想返利到账后在通知中心看到「发帖返利」而不是笼统的「积分变动」，以便确认这笔收益来源
13. 作为发帖用户，我想删除已返利的帖子不会导致积分被追回，以便正常使用没有后顾之忧
14. 作为运营，我想每天一次的限次由数据库唯一键保证，以便多实例并发下也不会超发
15. 作为运营，我想返利规则关闭期间发帖不占用当天名额，以便当天稍后开启规则用户仍能领取
16. 作为运营，我想领取时快照当时的返利值，以便领取后修改规则不影响已承诺的金额
17. 作为运营，我想同一帖子只产生一条领取记录，以便重复消息或重放不会重复发放
18. 作为客服，我想能看到每条帖子的返利领取状态与快照金额，以便用户反馈「没收到返利」时可以定位
19. 作为系统，我想在发帖事务内落一条领取任务 outbox，以便进程在同步领取前崩溃也能重放
20. 作为系统，我想同步领取成功后把领取任务标记已处理，以便扫描任务不会重复投递
21. 作为系统，我想同步领取失败时保留 PENDING 任务并返回「发放中」，以便用户不会被假金额误导
22. 作为系统，我想领取重放按（用户、行为、行为 ID）幂等，以便无论重放多少次结果一致
23. 作为系统，我想入账消息携带领取快照并使用确定性 msgId，以便重复投递不会产生第二条入账消息
24. 作为系统，我想积分和抽奖次数入账分别保持桶唯一键幂等，以便单边失败重试不会重复发放
25. 作为系统，我想入账消费失败继续由 broker 重试，以便临时故障可以自愈
26. 作为运维，我想返利入账死信可以通过现有 outbox 死信状态观察，以便出问题时人工介入
27. 作为前端，我想发帖接口返回结构化的返利状态与金额，以便按状态组装 toast 文案
28. 作为老客户端，我想忽略新增字段时仍能正常发布，以便接口升级不破坏兼容
29. 作为验收人员，我想只通过发帖 HTTP 接口就能验证四种提示状态，以便不需要深入内部实现
30. 作为维护者，我想移除「先同步发返利、失败再走 MQ」的过时注释与旧消费者语义，以便代码与设计一致

## Implementation Decisions

### 术语与边界

- 使用 `CONTEXT.md` 的「行为返利」「积分桶」「抽奖次数桶」「幂等」；不引入「返利账户」「返利余额」。
- 遵守 ADR-0016：领取的一次原子操作（读规则快照 + 插 claim + 插 credit outbox）落在返利仓储的一个本地事务里；跨域编排（发帖 → 领取、入账 → 积分/抽奖）在 case 层顺序调用，不开跨域事务。

### 两段式流程

1. **发帖事务**（post 仓储）：写 post + feed 索引 + **领取任务 outbox**（PENDING，msgId `post-rebate-claim:{postId}`，载荷含 postId、userId、periodKey）。search-sync outbox 仍按现有方式在提交后同步发送；领取任务不在发帖仓储内发送。
2. **同步领取**：发帖事务提交后，case 层调用返利域 `claimActionRebate(actionType=post, userId, refId=postId, periodKey)`。
3. **标记任务**：同步领取正常返回（无论 CLAIMED / DENIED / NO_REBATE）后，case 通过 post 域把领取任务标记已处理；领取抛异常时任务保持 PENDING，响应状态为 PENDING。
4. **生成入账任务**：领取事务内若为 CLAIMED，再插一条 credit outbox（msgId `post-rebate-credit:{postId}`，载荷含 claimId、userId、postId、pointsValue、drawCountValue），提交后同步发送到 credit topic；发送失败留 PENDING 交 outboxScan。
5. **异步入账**：credit 消费者 → case → 积分/抽奖域按快照金额入账 → 返利域把 claim 标记 GRANTED。

### 领取状态与 API 响应

- 持久化状态：`CLAIMED`（已占名额、快照已定）→ `GRANTED`（积分与抽奖次数入账完成）。
- API 状态：`CLAIMED` / `DENIED`（当天名额已被其他帖子领取）/ `NO_REBATE`（无开放规则或金额为 0）/ `PENDING`（同步领取异常，等待重放）。
- 发帖响应改为 `{postId, rebateState, pointsRebate, drawCountRebate}`；`DENIED`、`NO_REBATE`、`PENDING` 的两个金额均为 0。
- 直接发布与草稿提交两个接口都返回该结构；保存、编辑草稿不变。

### 数据模型

新增返利域表 `action_rebate_claim`：

```sql
CREATE TABLE IF NOT EXISTS action_rebate_claim (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    claim_id         VARCHAR(36) NOT NULL COMMENT '业务 ID（UUID）',
    user_id          VARCHAR(36) NOT NULL COMMENT '用户 ID',
    action_type      VARCHAR(16) NOT NULL COMMENT '行为类型 sign / pay / post',
    period_key       VARCHAR(16) NOT NULL COMMENT '限次周期键，日限=yyyyMMdd，发帖事务里定死',
    ref_id           VARCHAR(36) NOT NULL COMMENT '本次行为 ID（postId）',
    points_value     INT         NOT NULL DEFAULT 0 COMMENT '领取时快照的积分值',
    draw_count_value INT         NOT NULL DEFAULT 0 COMMENT '领取时快照的抽奖次数',
    state            VARCHAR(16) NOT NULL COMMENT 'CLAIMED / GRANTED',
    create_time      DATETIME    NOT NULL,
    update_time      DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_claim_id (claim_id),
    UNIQUE KEY uk_user_action_period (user_id, action_type, period_key) COMMENT '日限：同人同行为同周期一次',
    UNIQUE KEY uk_user_action_ref (user_id, action_type, ref_id) COMMENT '同一次行为只领取一次，重放幂等',
    KEY idx_state_time (state, update_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '行为返利领取记录（限次 + 快照）';
```

- `period_key`：日限口径 `yyyyMMdd`，时区固定 `Asia/Shanghai`；在发帖或提交时定死并随领取任务落库，重放使用任务里的值，不使用重放当天日期，避免跨零点串天。
- `points_value` / `draw_count_value`：领取时的规则快照。两者都为 0 时不插入 claim 行（不占名额），返回 `NO_REBATE`。
- 同一 ref 冲突视为幂等：返回已有 claim；同周期不同 ref 冲突返回 `DENIED`。

### 幂等设计

- 领取：`uk_user_action_ref` 保证同一帖子只领取一次；`uk_user_action_period` 保证同人同行为同周期只领取一次。
- 入账消息：确定性 msgId `post-rebate-credit:{postId}` 撞 `mq_outbox.uk_msg_id`，不产生第二条。
- 入账：积分桶和抽奖次数桶既有 `uk (user_id, reason_type, ref_id)` 吸收重复；两个域独立重试，单边成功另一边重试不会重复。
- 领取任务重放：任务 msgId 确定、领取按 ref 幂等，重复投递零副作用。

### 模块与接口

- **types**：新增 claim/credit topic 常量、`PostRebateClaimTaskMessage`（由现有 `PostRebateTaskMessage` 演进，增加 `periodKey`）、`PostRebateCreditMessage`、`RebateClaimState` 枚举、领取任务 msgId 构造器。
- **domain/rebate**：新增 `RebateClaimEntity`、`RebateClaimResultVO`；`IRebateRepository` 增加领取原子方法、按 ref/claimId 查询、标记 GRANTED；`IRebateService` 增加 `claimActionRebate`，负责读 open 规则、构建快照、调用仓储一次并返回结果。仓储实现负责领取事务与 credit outbox。
- **domain/post**：发帖和草稿提交返回「postId + 发布时间」结果对象；仓储写领取任务 outbox，不再发送旧返利消息；新增按确定性 msgId 标记领取任务已处理的方法。`IPostService.publishPost` 与 `submitPostDraft` 返回该结果对象。
- **domain/points、domain/raffle**：增加按显式金额入账的返利方法（`reason_type=REBATE`，refId=postId），供 credit 消费者使用；现有按 actionType 读配置的方法保留给其他行为。
- **case**：新增 `PostRebateCase`（同步领取、领取重放、入账编排）；`PostPublishCase` 改为「发布 → 领取 → 返回结果」；旧 `grantPostRebate` 退役。
- **infrastructure**：返利仓储实现领取事务（`TransactionTemplate`，REQUIRED），新增 claim DAO/PO 与 mapper XML；`schema.sql` 增加表；移除发帖仓储中「同步发送旧返利消息」的路径。
- **trigger**：`PostRebateClaimConsumer`（重放领取任务）、`PostRebateCreditConsumer`（入账）；`PostController` 的发帖和草稿提交接口返回新 DTO；旧 `PostRebateConsumer` 退役。
- **api**：新增 `PostPublishResponseDTO`。
- **front**：`createPost` 与 `submitPostDraft` 返回结构；`PostPublishView` 按 `rebateState` 组装 toast。

### 兼容与部署

- 旧 topic `bs-post-rebate` 与旧消费者退役。上线前尽量排空在途消息；若无法排空，领取消费者对缺少 `periodKey` 的旧载荷按「消费当天」兜底，避免丢消息。
- 老前端忽略新增字段仍可发布（HTTP 200 与 postId 语义不变）。

### 通知

- 积分入账沿用现有 `bs-points-changed` 通知链路；`MarketingNoticeCase` 文案补 `REBATE -> 发帖返利`，避免显示成「积分变动」。
- 抽奖次数入账本期不新增通知（提示由发帖 toast 覆盖），后续需要时再加。

### 产品口径（本 spec 默认）

- 每天 = `Asia/Shanghai` 自然日 0 点。
- 积分与抽奖次数共用一次领取名额（按行为一次，而不是每种返利各一次）。
- 规则全关或金额为 0 不占名额。
- 领取后删帖不回收返利、不释放名额（防刷）。
- 当前发帖无审核门槛；若以后引入先审后发，领取时机另行调整。

## Testing Decisions

- **按用户明确指示：本次实现完成后不要求运行测试。** 以下内容仅记录测试接缝，供后续需要时补充，不纳入本次交付验收。
- 好测试只验证外部行为：HTTP 响应的 `rebateState` 与金额、领取表唯一约束效果、重复投递后桶余额不变；不断言内部方法调用次数或 SQL 形态。
- 首选既有最高接缝：
  - **HTTP 接缝**：沿用 `bs-app` 现有集成测试基座，覆盖直接发布、草稿提交、同日第二条、规则关闭、并发或重复提交。
  - **MQ / outbox 接缝**：沿用 `RaffleShippingOutboxIntegrationTest` 一类的 outbox + 消费者集成方式，覆盖领取任务重放和入账重复投递。
  - **领域接缝**：`IRebateService.claimActionRebate` 的单元测试，覆盖 CLAIMED / DENIED / NO_REBATE 与快照。
- 先例：`PointsRebateIntegrationTest`、`DrawCountBucketIntegrationTest`、`PostDraftBoxIntegrationTest`、`RaffleShippingOutboxIntegrationTest`、`MarketingNoticeIntegrationTest`。
- 本次验收方式：编译通过 + 手工按四种响应状态各走一遍（直接发布与草稿提交各覆盖一次）。

## Out of Scope

- sign / pay 等其他行为的每日限次；本期只把 post 接入领取表，表和接口保持通用，其他行为不迁移。
- 返利撤销或回收：删帖、封号、退款等场景不追回已领取返利，也不释放名额。
- 先审后返：本期发帖直接 NORMAL，不含审核通过才返利的逻辑。
- 运营后台的领取记录查询页、人工补发工具。
- 抽奖次数变动通知。
- 可配置周期（每周 / 每月 / 每 N 小时）；本期只落地日限 `yyyyMMdd`，表保留 `period_key` 以便扩展。
- 自动化测试的编写与执行（按用户要求）。
- 前端 postId 从 number 到 string 的全局清理；仅在本次触碰的接口新 DTO 中使用 string。

## Further Notes

- 本次顺带清理 `PostRebateTaskMessage` 与 `PostPublishCase` 中「提交后先同步调返利、成功就不发 MQ」的过时注释；实际实现早已是无条件 MQ。
- `creditForRebate(actionType, ...)` 保留给其他行为；post 的入账使用显式金额重载，避免消费端再读规则。
- 领取表会随发帖量增长；`idx_state_time` 供后续对账扫描（CLAIMED 超时未 GRANTED）使用，本期不实现扫描。
- 时区：`period_key` 生成必须显式使用 `Asia/Shanghai`，不要依赖 JVM 默认时区。
- 若未来需要「提示 = 最终到账」而不是「提示 = 已领取」，可把入账改为同步，或增加到账后的通知确认；本 spec 不覆盖。
