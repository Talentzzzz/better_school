# 01: 共享契约 — types 常量/枚举/消息 + `action_rebate_claim` 表 + 发帖响应 DTO

**What to build:** spec「Implementation Decisions / 模块与接口」中的 types 与数据模型部分，作为后续所有票的共享契约。**只做增量，不删旧类**（旧 topic/旧消息类由票 06 退役，避免中途引用断裂）。

**Blocked by:** None (can start immediately)

**Status:** resolved

## 交付物（严格按此签名，其他票按此引用）

### 1. `bs-types` 常量（`com.betterschool.types.common.Constants`）

新增（旧 `POST_REBATE_TOPIC` 暂时保留，票 06 删除）：

```java
public final static String POST_REBATE_CLAIM_TOPIC = "bs-post-rebate-claim";
public final static String POST_REBATE_CREDIT_TOPIC = "bs-post-rebate-credit";
```

### 2. `com.betterschool.types.rebate.RebateClaimState`（持久化状态）

枚举 `CLAIMED`、`GRANTED`。落库列 `action_rebate_claim.state`，只这两个值。

### 3. `com.betterschool.types.rebate.RebateClaimApiState`（API 状态）

枚举 `CLAIMED`、`DENIED`、`NO_REBATE`、`PENDING`。这是发帖响应的 `rebateState` 取值。

### 4. `com.betterschool.types.rebate.RebatePeriodKeys`

```java
public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
/** 由已落库的发布时间（数据库时钟的 LocalDateTime）格式化为 yyyyMMdd，不依赖 JVM 默认时区 */
public static String of(LocalDateTime publishTime);
/** 当天兜底（仅旧载荷缺 periodKey / 重放兜底用），显式 Asia/Shanghai */
public static String today();
```

`of(null)` 抛 `IllegalArgumentException`（或直接 `Objects.requireNonNull`）。

### 5. `com.betterschool.types.common.PostRebateClaimTaskMessage`

`@Data @NoArgsConstructor @AllArgsConstructor`，字段：
`String postId; String userId; String periodKey;`

### 6. `com.betterschool.types.common.PostRebateCreditMessage`

`@Data @NoArgsConstructor @AllArgsConstructor`，字段：
`String claimId; String userId; String postId; int pointsValue; int drawCountValue;`
（`pointsValue` / `drawCountValue` 用 `int`，与快照列一致；为 0 表示该种返利不发。）

### 7. `PostRebateTaskKeys` 增加确定性 msgId

现有 `of(postId)`（`post-rebate:` 前缀）保留给票 06 删除。新增：

```java
public static String claimOf(String postId);  // "post-rebate-claim:" + postId
public static String creditOf(String postId); // "post-rebate-credit:" + postId
```

注释说明长度：领取任务前缀 18 + UUID 36 = 54，入账任务前缀 19 + UUID 36 = 55，均小于 `mq_outbox.msg_id VARCHAR(64)`。

### 8. `bs-api` 新增 `PostPublishResponseDTO`

`com.betterschool.api.dto.PostPublishResponseDTO`，`@Data @NoArgsConstructor @AllArgsConstructor`：

```java
private String postId;
/** CLAIMED / DENIED / NO_REBATE / PENDING */
private String rebateState;
private int pointsRebate;
private int drawCountRebate;
```

### 9. `sql/schema.sql`

在 `action_rebate_config` 之后追加 spec 中的 `action_rebate_claim` 建表语句（原样：`uk_claim_id`、`uk_user_action_period`、`uk_user_action_ref`、`idx_state_time`）。
顺手把 `action_rebate_config.action_type` 列注释从 `sign(签到) / pay(支付)` 补成 `sign(签到) / pay(支付) / post(发帖)`。

## 验收（本环境无 JDK21/Maven，做静态自检）

- [x] 新增文件包名/类名与上文完全一致，其他票可直接 import
- [x] `action_rebate_claim` DDL 与 spec 逐列一致
- [x] 没有删除 `PostRebateTaskMessage` / `POST_REBATE_TOPIC` / `PostRebateTaskKeys.of`（票 06 统一退役）
- [x] 全仓不出现对尚未创建类的引用（本票只加不删，旧引用保持原样）

## Comments

**2026-11-26** — 已实现并静态自检（无 JDK21/Maven，未编译）。

改动：

- `bs-types/.../common/Constants.java`：新增 `POST_REBATE_CLAIM_TOPIC`、`POST_REBATE_CREDIT_TOPIC`（旧 `POST_REBATE_TOPIC` 保留）。
- `bs-types/.../rebate/RebateClaimState.java`（新）：持久化状态 `CLAIMED` / `GRANTED`。
- `bs-types/.../rebate/RebateClaimApiState.java`（新）：API 状态 `CLAIMED` / `DENIED` / `NO_REBATE` / `PENDING`（调用方用 `name()`）。
- `bs-types/.../rebate/RebatePeriodKeys.java`（新）：`ZONE` + `of(LocalDateTime)` + `today()`；`of(null)` 经 `Objects.requireNonNull` 抛 NPE。
- `bs-types/.../common/PostRebateClaimTaskMessage.java`（新）：`postId/userId/periodKey`。
- `bs-types/.../common/PostRebateCreditMessage.java`（新）：`claimId/userId/postId/pointsValue/drawCountValue`。
- `bs-types/.../common/PostRebateTaskKeys.java`：新增 `claimOf` / `creditOf`，保留 `of`。
- `bs-api/.../dto/PostPublishResponseDTO.java`（新）：`postId/rebateState/pointsRebate/drawCountRebate`。
- `sql/schema.sql`：`action_rebate_config` 之后追加 `action_rebate_claim`（spec DDL 原样）；`action_rebate_config.action_type` 注释补 `post(发帖)`。

静态自检：全仓 grep 新类名仅有本次新增文件自身命中（无悬空引用）；逐文件核对 package/import/括号；Lombok 在 `bs-types` / `bs-api` 的 pom 中已声明；git diff 仅含上述增量。

注：`PostRebateTaskKeys` 头注释里的 msgId 长度按实际字符数写为「领取 18+36=54、入账 19+36=55」（票面示例写 17+36=53，少算了冒号），均小于 `VARCHAR(64)`。

**未编译**：本机无 JDK21/Maven，验收以静态检查为准。

