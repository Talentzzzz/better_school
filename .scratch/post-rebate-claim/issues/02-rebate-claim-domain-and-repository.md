# 02: 返利域 — `claimActionRebate` 原子领取 + credit outbox

**What to build:** 返利域新增行为返利领取能力：读 open 规则、构建金额快照、一次本地事务内插 claim + credit outbox，按唯一键处理 CLAIMED / DENIED / 幂等重放。遵循 ADR-0016：事务在仓储实现内，域服务只调一次仓储。

**Blocked by:** 01

**Status:** resolved

## 契约（其他票直接依赖）

### domain/rebate/model/entity/`RebateClaimEntity`

字段：`String claimId; String userId; String actionType; String periodKey; String refId; int pointsValue; int drawCountValue; RebateClaimState state; LocalDateTime createTime; LocalDateTime updateTime;`
（`state` 落库值 `CLAIMED`/`GRANTED`；成员字段保持业务裸名。）

### domain/rebate/model/valobj/`RebateClaimResultVO`

```java
private RebateClaimApiState apiState;      // CLAIMED / DENIED / NO_REBATE / PENDING
private RebateClaimState persistedState;   // CLAIMED / GRANTED；DENIED/NO_REBATE/PENDING 时为 null
private String claimId;                    // DENIED/NO_REBATE/PENDING 时 null
private String refId;                      // 本次行为 ID（postId），用于响应拼装
private int pointsValue;
private int drawCountValue;
```

提供静态工厂（或构造器）：`claimed(...)`、`denied(String refId)`、`noRebate(String refId)`、`pending(String refId)`、`granted(...)`。`denied`/`noRebate`/`pending` 两个金额恒 0。

### `IRebateRepository` 新增

```java
/** 原子领取：查重 + 插 claim + （CLAIMED 时）插 credit outbox，仓储内一个 REQUIRED 本地事务；提交后同步发送 credit 消息 */
RebateClaimResultVO claim(RebateClaimEntity claimEntity);

/** 按 (userId, actionType, refId) 查领取记录；无则 null */
RebateClaimResultVO findClaimByRef(String userId, String actionType, String refId);

/** 按 claimId 查领取记录；无则 null（客服排障用） */
RebateClaimResultVO findClaimByClaimId(String claimId);

/** CLAIMED → GRANTED；条件 UPDATE，幂等 */
void markGranted(String claimId);
```

### `IRebateService` 新增

```java
/**
 * 行为返利同步领取。读 action_rebate_config 两条 open 规则（POINTS / DRAW_COUNT）构快照；
 * 两者都为 0 或都未开放 → NO_REBATE（不插 claim 行、不占名额）；否则调仓储 claim 一次。
 * @param periodKey 限次周期键（yyyyMMdd，Asia/Shanghai，由发帖事务定死）
 */
RebateClaimResultVO claimActionRebate(String actionType, String userId, String refId, String periodKey);
```

入参非法（null/blank）抛 `AppException(ResponseCode.CONFIG_PARAM_ERROR)`（与本域现有风格一致）。

## 实现要点

- `RebateServiceImpl` 只注入 `IRebateRepository`；用 `findOpenRebateValue(actionType, RebateTypeVO.POINTS / DRAW_COUNT)` 读值，`null -> 0`。
- 两者都为 0 → `RebateClaimResultVO.noRebate(refId)`，**不落库**。
- 否则 `claimId = UUID.randomUUID().toString()`，`state = CLAIMED`，调 `rebateRepository.claim(...)`。
- 仓储 `claim`：
  1. `TransactionTemplate`（传播 REQUIRED，由 app 的 `TransactionConfig` 注入）内：
     - `selectByRef` 命中 → 直接返回 CLAIMED（快照取已有行；即使已 GRANTED，API 状态仍 CLAIMED，`persistedState` 返回真实值），**不插 outbox、不发送**；
     - `selectByUserActionPeriod` 命中（不同 ref）→ DENIED；
     - `insert` claim；捕获 `DuplicateKeyException` 后重查 ref：命中 → CLAIMED（幂等），未命中 → DENIED；
     - 插入 credit outbox：`MqOutbox.pending(PostRebateTaskKeys.creditOf(refId), Constants.POST_REBATE_CREDIT_TOPIC, new PostRebateCreditMessage(claimId, userId, refId, pointsValue, drawCountValue), objectMapper)`；
     - 返回 claim VO + outbox 行。
  2. 事务提交后同步 `rocketMQTemplate.syncSend` credit 消息；`SEND_OK` → `markSentByMsgId`；失败累计 `retry_count`，达 `MqOutbox.MAX_RETRY_COUNT` 置死信（复用 PostRepository 的 `sendOutbox` 逻辑，不要抽公共组件）。
- 新 DAO/PO/mapper：
  - `infrastructure/dao/rebate/po/ActionRebateClaim.java`（表列同名驼峰）
  - `infrastructure/dao/rebate/IActionRebateClaimDao.java`：`insert`、`selectByRef`、`selectByUserActionPeriod`、`selectByClaimId`、`markGranted`
  - `app/src/main/resources/mybatis/mapper/rebate/ActionRebateClaimMapper.xml`
  - SQL 单表、零 JOIN、零表别名（遵守仓库 SQL 规范）；时间列 `NOW()`。
- 所有查询映射到 PO 的 `state` 为字符串，Java 侧 `RebateClaimState.valueOf`。

## 验收

- [ ] CLAIMED 时 claim 行 + credit outbox 行在同一事务；outbox `msg_id = post-rebate-credit:{postId}`、`destination = bs-post-rebate-credit`
- [ ] 同 ref 重放返回已有快照，不产生第二条 claim/outbox
- [ ] 同周期不同 ref 返回 DENIED，且不落 claim 行、不占名额
- [ ] 金额全 0/未开放返回 NO_REBATE，不落任何行
- [ ] `markGranted` 是条件 UPDATE（`WHERE claim_id = ? AND state = 'CLAIMED'`），重复调用无副作用

## Comments

**2026-11-26 — resolved（ticket 02）**

实现文件：

- `bs-domain/.../domain/rebate/model/entity/RebateClaimEntity.java`（新增）
- `bs-domain/.../domain/rebate/model/valobj/RebateClaimResultVO.java`（新增；`claimed` / `granted` / `denied` / `noRebate` / `pending` 静态工厂）
- `bs-domain/.../domain/rebate/adapter/repository/IRebateRepository.java`（`claim` / `findClaimByRef` / `findClaimByClaimId` / `markGranted`）
- `bs-domain/.../domain/rebate/service/IRebateService.java`、`RebateServiceImpl.java`（`claimActionRebate`）
- `bs-infrastructure/.../dao/rebate/po/ActionRebateClaim.java`（新增）
- `bs-infrastructure/.../dao/rebate/IActionRebateClaimDao.java`（新增）
- `bs-app/src/main/resources/mybatis/mapper/rebate/ActionRebateClaimMapper.xml`（新增）
- `bs-infrastructure/.../adapter/repository/rebate/RebateRepository.java`（领取事务 + credit outbox + 提交后同步发送）

设计要点：

- **加锁读**：领取事务内的 `selectByRef` / `selectByUserActionPeriod`（含撞唯一键后的重查）都是
  `SELECT ... FOR UPDATE`。REPEATABLE READ 的普通快照读看不到并发事务提交的新行，撞 `uk_user_action_ref`
  后若用普通读重查会把幂等重放误判成 DENIED 或再次插入；加锁读才能拿到最新已提交状态。
  事务外排障读（`findClaimByRef` / `findClaimByClaimId`）保持普通 SELECT。
- 事务形态：`TransactionTemplate.execute`（REQUIRED）内「查重 → 插 claim → 插 credit outbox」，提交后
  才 `syncSend`；发送逻辑与 `PostRepository.sendOutbox` 一致（SEND_OK → `markSentByMsgId`，否则累计
  `retry_count` / 达 `MqOutbox.MAX_RETRY_COUNT` 置死信），未抽公共组件。
- 重放语义：同 `(userId, actionType, refId)` 命中直接返回已有快照，**不插 outbox、不发送**；已 GRANTED 的行
  API 状态仍 CLAIMED，`persistedState` 返回 GRANTED（`RebateClaimResultVO.granted`）。
- 同周期不同 ref → DENIED；金额快照两者为 0 / 未开放 → 服务层直接 NO_REBATE，不调仓储、不落行。
- `markGranted` 为条件 UPDATE：`WHERE claim_id = #{claimId} AND state = 'CLAIMED'`；DAO 返回影响行数，
  重复调用 0 行无声副作用。
- DAO 比票面多一个 `selectByRefNoLock`：`selectByRef` 按票面要求是加锁读，而 `findClaimByRef` 必须是普通读，
  因此拆出同条件的不加锁查询；未新增其他方法。
- 按用户要求**未写测试**；本机无 Maven/JDK21，未能编译，已逐文件通读 + grep 全部新符号 + XML 结构校验。

**2026-11-26 — replay ordering fix**

`RebateServiceImpl.claimActionRebate` 改为**先按 `(userId, actionType, refId)` 查已有领取**：命中直接返回已有
快照（不读当前规则、不插行）；未命中才读两条 open 规则构快照，两者为 0 返回 NO_REBATE，否则构建实体调
`claim`。原因：规则可能在领取成功后由运营关闭，若先读规则会把同一 ref 的重放误判成 NO_REBATE，违背
「同一 ref 冲突视为幂等 / 无论重放多少次结果一致」。仓储内 `FOR UPDATE` 查重与撞唯一键后的加锁重查保持不变。
`IRebateService` 与实现类 javadoc 同步改为该顺序。

