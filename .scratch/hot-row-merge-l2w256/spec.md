# spec：热点行写入换进程内合并（L2W256）

Status: 定稿（口径来自 `map.md` 与票 01–10，全部 resolved）
Date: 2026-10-23
Scope: `backend/better-school/`（社区计数）+ `backend/marketing/`（库存配额）

> **本文只写形态、删什么、怎么上怎么退。** 判据不在本文，在 [`acceptance.md`](acceptance.md)。
> 上游决策一律不重开：票号即出处，遇到「为什么不是另一种」回票里看 `## Answer` 与 `## Comments`。

## 索引

| 章节 | 内容 | 来源票 |
|---|---|---|
| 1 | 目的地、五条链路、三档口径、边界 | map / 02 / 04 |
| 2 | 合并器的形态（两份实现 / 两个入口 / 参数 / 5 条不变量 / 背压 / 观测） | 06 |
| 3 | 计数侧写入与读取 | 02 / 03 |
| 4 | 库存侧落库与确认 | 05 / 07 |
| 5 | 删什么（文件级 + DDL + topic + 注册） | 05 / 07 / 08 |
| 6 | 上线 8 步、回滚、D9 收口、三份脚本 | 09 |
| 7 | 验收 | 10 + `acceptance.md` |
| 8 | 受影响的测试与文档 | 08 §6 / `acceptance.md` §4 |
| 9 | 已知窗口与残留风险 | 06 §10 / 08 §2 / 09 |
| 10 | 落地票索引 | 本文 |

## 1 目的地与边界

### 1.1 换的是什么

五条「逐笔打同一行」的写，统一换成**进程内分片缓冲 + 定时窗口 + 共享固定执行池 + 合并成一条 `UPDATE`**：

| # | 链路 | 打的行 | 今天怎么走 | 换完怎么走 |
|---|---|---|---|---|
| 1 | 帖子点赞 / 取消赞 | `post_like_count` 一行 + `post_like` 一批 | `INSERT` + 同事务 outbox → `bs-post-count` → `PostCountAggregator`（200ms 单线程）→ 窄表 `UPDATE` | 请求等 `windowMs`，一组一事务：批量 `INSERT IGNORE`/`DELETE` + 一条 `UPDATE` |
| 2 | 评论点赞 | `post_comment.like_count`（主表行） | 同事务直写（已经精确） | 同一份合并器，只是落库方法不同 |
| 3 | 帖子浏览量 | `post.view_count`（主表行） | outbox → `bs-post-count` → 聚合器 | fire-and-forget，窗口后一条 `UPDATE` |
| 4 | 商品浏览量 | `product.view_count` | outbox → `bs-product-count` → 聚合器 | 同上 |
| 5 | 库存配额预扣 / 兑换确认 | `*_inventory_quota` / `*_marketing_quota` 一行台账 | Redis 桶 Lua 预扣 → 明细 → 同事务 outbox（`delay_ms = 2000`）→ `bs-inventory-merge` → 按时间格合两格 → 60s 兜底 | 请求等 `windowMs`，一组一事务：明细 `INSERT IGNORE` + 一条 `UPDATE` 台账 |

**收益口径要老实**：合并的收益来自「同一行的并发」。单热点下合并比随速率涨；**长尾下合并比 ≈ 1，合并器在那条路上是净成本**（多一次排队 + 5ms），这是选「统一一条路」的代价，不是待优化项（票 06 §2）。

### 1.2 三条语义底线

| 链路 | 一致性 | 失败形态 | 真相源 |
|---|---|---|---|
| 库存预扣 / 确认 | **成功 = 已提交**（等 future，`COMMIT` 之后才说成功） | 事务回滚 → 调用方拿失败，凭 `bizNo/bizId` 幂等重试 | 台账三列 + 明细唯一键 |
| 点赞（帖子 / 评论） | **与关系行同事务，精确** | 整体回滚 → `RETRYABLE`，唯一键保证重试不重复计 | `post_like` / `post_comment_like` 条数 |
| 浏览量（帖子 / 商品） | 异步 +1，**允许丢** | 只计丢弃数（`droppedViews`），不告警、不回补 | 无（自己就是唯一记录） |
| 评论总数 / 子回复数 | **不动**，仍精确、与审核动作同事务 | 不变 | `post_comment` 行 |

### 1.3 边界（不做）

- 不改 `post_like` / `post_comment_like` 的表结构与唯一键（关系表是事实源）。
- 不做分库分表 / 读写分离；合并后的写仍是单库单表单行。
- 不实现跨实例 key 路由，只留 shade key 口子（§2.8）。
- 不下线 RocketMQ / Redis / xxl-job——只摘这三条链路专属的那几个 topic / 消费组 / 桶 / 兜底注册。
- 不动抽奖概率表预热、评论审核链路、通知链路、聊天推送。
- **不做压测验收**（票 10），不给 TPS / p99 阈值。

## 2 形态：两份合并器

### 2.1 为什么是两份，不是共享构件

用户 2026-10-22 否决「独立构件 / 第三个 reactor / 两家互相依赖」：两个工程各自在自己的 Spring Boot 里实现一份，跟着自己的 `@Component` / `@Transactional` / `@PreDestroy` 走。**对齐的是「算法 + 5 条不变量 + 参数名」，不是代码长相**（票 06 §9）。

| | better-school（计数） | marketing（库存） |
|---|---|---|
| 包 | `com.betterschool.infrastructure.adapter.merge` | `com.betterschool.marketing.infrastructure.inventory.merge` |
| 组件 | `CountMerger`（`@Component`，计数侧唯一实例） | `QuotaMergeBuffer`（`@Component`） |
| 包内 | `ShardBuffer`（非 `public`）、`Pending` | 同左 |
| 入口 | `submitAndWait`（点赞类）+ `submitAsync`（浏览） | 只有 `submitAndWait` |
| 落库方法 | `@Transactional flushGroup(CountActionEnum, long key, List<CountEvent>)` | `@Transactional flushDeductGroup(...)` / `flushClaimGroup(...)` |
| 停机 | `@PreDestroy close()`（停收 → 排空 → 关池） | 同左 |

### 2.2 API 形状

**否决 `submit(key, payload, waitMode)`**：等结果的人要结果对象，不等的人连 `CompletableFuture` 都不该分配（10k/s 的浏览 = 每秒一万个 future + park/unpark）。两个入口，各自工程只写自己需要的那个。

```java
// ── better-school：计数侧
@Component
public class CountMerger {
    MergeOutcome<LikeStateEnum> submitAndWait(long shardKey, CountEvent event); // 点赞 / 取消赞 / 评论赞
    SubmitOutcome submitAsync(long shardKey, CountEvent event);             // 浏览
    int pending();
    boolean awaitIdle(long timeoutMs);
    MergeMetrics metrics();
    @PreDestroy void close();
}

public enum CountActionEnum { POST_LIKE, POST_UNLIKE, COMMENT_LIKE, POST_VIEW, PRODUCT_VIEW }
public record CountEvent(CountActionEnum action, long targetId, long userId) { }
public enum SubmitOutcome { ACCEPTED, BUFFER_FULL, CLOSED }
public enum MergeState { COMPLETED, TIMEOUT, BUFFER_FULL, CLOSED, FAILED }
public record MergeOutcome<T>(MergeState state, T result) { }
public record MergeMetrics(int pending, long accepted, long flushed, long batches,
                           long rejectedFull, long rejectedClosed, long timedOut,
                           long failed, long drainedFailed, long maxFlushNanos) { }
```

```java
// ── marketing：库存侧（没有 fire-and-forget 的调用方，不为对称造方法）
@Component
public class QuotaMergeBuffer {
    DeductResultEnum submitAndWait(long invId, InventoryDeductRequestVO request); // 枚举名沿用票 05 的三出口
    ClaimOutcomeEnum submitAndWait(long invId, ClaimItem item);                    // 兑换确认（票 07）
    int pending();
    boolean awaitIdle(long timeoutMs);
    MergeMetrics metrics();
    @PreDestroy void close();
}
```

- `MergeMetrics` 字段名、`ShardBuffer` 的方法名（`offer` / `runFlush` / `bringForward` / `failRemaining` / `isIdle`）两边必须一样，方便对照与调参。
- marketing 侧的两个 `submitAndWait` 是**重载**（靠入参类型区分：预扣 `InventoryDeductRequestVO`、确认 `ClaimItem`，后者就是票 07 §5 的 `submitAndWait(invId, item)`），内部落到两个各自 `@Transactional` 的组方法——**不合成一个方法**，因为两个失败域的语义不同（§4.5）。
- **`better-school` 内是一个实例**：点赞（等）与浏览（不等）共用同一份分片与线程池，落库时按「动作类型 × shardKey」二次分组；两个实例要 2×256 个缓冲，而它们打的是同一批热点帖。

### 2.3 分片与路由

- **`shardKey` = 这一批要更新的那一行的主键，由调用方显式给**，合并器不解析 payload（否则将来网关按 key 分流时两层路由会分叉）：
  - 帖子点赞 / 取消赞 / 帖子浏览 → `postId`；评论点赞 → `commentId`；商品浏览 → `productId`；库存预扣 / 确认 → `invId`（两段共用，撞片无害）。
  - 同一条业务行在所有调用点必须给同一个 key（点赞与浏览都用 `postId`，哪怕打的是两行）——这条靠「key 只从行主键取」的代码约束，单测锁不住。
- **路由高位折叠**：`int shardOf(long key) { return (int) ((key ^ (key >>> 32)) & mask()); }`
  - `key < 2^32` 时等价于 `key & mask`（本仓库 `post_id` 稠密、`inv_id` 是两位配置），但雪花 ID / 分表拼接 ID 不会全挤 0 号片。合并器内部实现，不外泄。

### 2.4 参数（两套默认值，均未扫参）

| 参数 | 硬约束 | 计数 | 库存 | 依据 |
|---|---|---|---|---|
| `shardCount` | 2 的幂，≥ 峰值并发热点数 | **256** | **64** | 分片只买「批纯度」，并行度由线程数定 |
| `windowMs` | ≥ 0 | **5** | **5** | 票 03 定：一套参数 |
| `maxBatch` | > 0 | **200** | **200** | 200 行 ≈ 8ms，再大 `waitTimeoutMs` 吃紧 |
| `waitTimeoutMs` | > `windowMs` | **100** | **100** | 5ms 窗口 + 8ms 落库 + 抖动 |
| `shardCapacity` | ≥ `maxBatch` | **5000** | **2000** | 只是「降级触发点 + 内存上界」；库存资金敏感 ⇒ 更早降级 |
| `flushThreads` | 1 ≤ n ≤ `poolSize − 2` | **4** | **2** | 见下 |
| `drainTimeoutMs` | > 0 | **5000** | **5000** | 照抄参考工程 |
| `setRemoveOnCancelPolicy(true)` | 必须 true | true | true | 攒满提前触发每秒 cancel 上千定时任务 |

**`flushThreads == poolSize` 被推翻**：参考工程是只做扣减的独立服务（池 20）；本仓库池是**共享的 `hikari.maximum-pool-size = 10` + 虚拟线程**，连接池就是真并发上限。搬成 10 的后果是 flush 占满连接、普通查询在 `connection-timeout: 3000` 上排队。所以 `MergeConfig` 只校验 `flushThreads ≥ 1`，`flushThreads ≤ 池 − 2` 的校验写在各自建实例的 `@Configuration` 里，并把两个数一起打进启动日志。

> **未扫参声明**：以上全部是模型推导。上线后只按 `MergeMetrics` 的 `flushed ÷ batches`（合并比）、`timedOut`、`rejectedFull` 调，**调参不改代码结构**。

### 2.5 五条不变量（单测逐条锁死）

| # | 不变量 | 说明 |
|---|---|---|
| **I1** | 不丢件、恰一个终态 | `ACCEPTED` 的件必被落库方法看到一次；每件至多回填一次（`Pending.complete` 幂等）；落库方法漏回填时合并器补 `FAILED` |
| **I2** | 单飞 | 同一分片任一时刻至多一条 flush 体在执行（`running` + `generation` 双保险）；推论 `scheduled == false ⟹ 缓冲空且无在途 flush`。**不能用 `Future.cancel()` 判单飞**（对运行中的任务也返回 true） |
| **I3** | 锁不跨 DB | 临界区只有内存操作，落库在锁外；锁用 `ReentrantLock`（虚拟线程阻塞在 `synchronized` 上会 pin 载体线程） |
| **I4** | 停机三步 | 停收 → 排空 → 关池，顺序不能反；排空超时把剩余件**显式回填**（浏览件回填是空操作，但必须计入 `drainFailed`） |
| **I5** | 背压有界 | 分片满 ⇒ `offer` 立刻 false，不阻塞、不扩容、不挤掉已入队件；**合并器自己永不丢弃已入队件** |

另外两条要断言但不叫不变量：单批 ≤ `maxBatch`；同分片内保持入队顺序（跨分片不保证）。

### 2.6 背压与降级：没有任何场景把 `REJECTED` 返回给用户

合并器只说三句：`ACCEPTED` / `BUFFER_FULL` / `CLOSED`；**降级动作全在 case 层**：

| 场景 | 缓冲满 | 停机中 | 等超时 | 用户看到 |
|---|---|---|---|---|
| 点赞 / 取消赞 / 评论赞 | 该请求同步走**同一个落库方法**（组大小 1） | 同左 | 回 `RETRYABLE` | 成功 或「请重试」 |
| 帖子 / 商品浏览 | 一次独立 `UPDATE … + 1`；再失败 ⇒ 丢弃并 `droppedViews++` | 丢弃并计数 | 不回填（本来没等） | 无感知 |
| 库存预扣 | 调用方线程同步扣一批（组大小 1） | 同左 | `TIMEOUT_UNKNOWN` | 「处理中」 |
| 兑换确认 | 调用方线程同步 `confirmIssued(invId, List.of(item))` | 同左 | 与 `FAILED` 合一，抛可重试 | 「处理中」（MQ 重投） |

两条硬要求：**降级必须复用同一个落库方法**（否则两套 SQL 迟早漂移，而漂移只在压力最大时暴露）；**丢弃必须计数**（`failed` / `drainedFailed` + case 层的 `droppedViews`）。

### 2.7 观测

`MergeMetrics` 只给快照 record，不依赖 micrometer、不开端点。四个用途：`pending`（背压预警）、`accepted − flushed`（丢没丢）、`flushed ÷ batches`（合并比，调参唯一依据）、`rejectedFull / rejectedClosed / timedOut / failed / drainedFailed / maxFlushNanos`。

### 2.8 多实例口子

划得住，但三个前提写进代码评审清单：① 合并器不得从 payload 推导 key；② 同一业务行所有调用点给同一个 `shardKey`；③ 网关分流方向是「同 key 同实例」，不是均匀分布——单帖热度的上限 = 单实例单分片的 flush 能力，这是前提不是缺陷。

## 3 计数侧（better-school）

### 3.1 写：点赞 / 取消赞 / 评论点赞

一个请求 = `submitAndWait(postId, CountEvent)` → 窗口到点后**一组一个事务**：

```sql
-- POST_LIKE（同批只碰一个 postId）
INSERT IGNORE INTO post_like (post_id, user_id, create_time) VALUES (...), (...);
INSERT INTO post_like_count (post_id, like_count, create_time, update_time)
     VALUES (?, ?, NOW(), NOW())
ON DUPLICATE KEY UPDATE like_count = like_count + ?, update_time = NOW();   -- 窄表只对有赞的帖子建行

-- POST_UNLIKE
DELETE FROM post_like WHERE post_id = ? AND user_id IN (...);
UPDATE post_like_count SET like_count = GREATEST(0, like_count - ?), update_time = NOW() WHERE post_id = ?;

-- COMMENT_LIKE（计数在主表行上，没有窄表）
INSERT IGNORE INTO post_comment_like (comment_id, user_id, create_time) VALUES (...), (...);
UPDATE post_comment SET like_count = like_count + ? WHERE id = ?;
```

- **批内部分撞唯一键要能定位到具体请求**：`INSERT IGNORE` 的 affected 只给行数，撞键时回查该批 `(post_id, user_id)`（同参考工程 `listDetailsByBizNos`），只把 `DUPLICATE` 回给命中的那几个。
- **`DUPLICATE` / 取消赞不存在** 是业务结果，不是异常：`likePost`/`unlikePost` 的 HTTP 语义不变（重复赞报错由唯一键裁决）。
- 事务失败 = 整体回滚，调用方拿 `RETRYABLE`；计数**不可能**与关系行脱节，因此不需要校准。

### 3.2 写：浏览（fire-and-forget）

`submitAsync(postId, POST_VIEW)` 入队即返回；窗口后一条 `UPDATE post SET view_count = view_count + N WHERE id = ?`（商品同构，打 `product`）。
没有关系行 ⇒ **没有真相表可对账，也不需要**。失败只记丢弃数。

### 3.3 读路径：详情缓存不存计数

- `PostDetailCache` 只缓存正文 / 状态 / 作者 / 配图；`like_count` / `comment_count` / `view_count` **不进缓存**。`PostEntity` 进缓存前把三个计数字段清掉。
- `PostMapper.selectDetail` 拆成「内容查询（进缓存）」+「计数查询（每次读，按主键一次取三个数）」，在 DTO 里拼。
- **收益**：`PostCountAggregator` 里那句「点赞刷库后 `postDetailCache.evict`」整条删除，合并器从此不知道缓存存在。
- 列表页仍走 `PostMapper.xml` 的 LEFT JOIN 投影（不进缓存 ⇒ 就是实时值），不改。
- **合并器不叠加在途增量**（读路径不认识合并器）：点赞请求返回时事务已提交，多实例下也不会读到陈旧值。

### 3.4 幂等与去重

| 项 | 处置 |
|---|---|
| msgId 环形去重（100k LRU + `synchronized`） | **删**：重投源头（MQ）消失，点赞幂等由唯一键承担，浏览本无幂等 |
| 前端双击 / HTTP 重试 | 由唯一键裁决（第二次 `DUPLICATE`），不引入短窗去重 |
| 刷盘失败并回窗口（at-least-once） | **删**：至多一次，失败只记 `failed`，不回补 |

## 4 库存侧（marketing）

### 4.1 落库：一组一个事务

分片键 `invId`；落库时按「动作类型（预扣 / 确认）× segment」二次分组，**一组一个事务**（不是一批一个事务：一组一事务让锁集最小、失败只回滚一组、能精确回填到件；一批一事务会让两个分片以不同顺序锁同两行，多一个死锁面）。

```java
// 预扣：正常路径 = N 行 INSERT IGNORE + 1 条 UPDATE
BEGIN
  INSERT IGNORE INTO <segment>_detail (inv_id, quantity, biz_type, biz_id) VALUES (...), (...);
  UPDATE <segment>_ledger
     SET surplus_qty = surplus_qty - N,
         waiting_qty = waiting_qty + N          -- 实物段
     --  issued_qty = issued_qty + N           -- 虚拟段直接进 issued
   WHERE inv_id = ? AND surplus_qty >= N;
COMMIT
```

### 4.2 扣不动：再探再切（不二分）

`affected = 0` 时 `SELECT surplus_qty` → `k = min(剩余, 批内笔数)`：
- `k > 0`：`UPDATE` 扣 `k`，前 `k` 笔回成功、其余回 `INSUFFICIENT`（**注意**：这条第二事务路径必须让明细与台账仍然平——插进去的是 `k` 行，不是 N 行）；
- `k = 0`：整批 `INSUFFICIENT`。

不照抄参考工程的 `soldoutShortcut` + 二分拆批：抽奖每笔恒为 1 件，批内数量相同，二分用不上；`soldoutShortcut` 属于落库方法，不进合并器。

### 4.3 重放与失败

- **整批撞 `uk_biz_type`**：扣减量 0、直接提交、按成功回放。
- **部分撞键**：回查命中行（`listDetailsByBizNos` 手法），从本次扣减量里剔除。
- **事务失败**：整体回滚 → 调用方 `ERROR` → 凭 `bizId` 重试（唯一键兜底，不会双扣）。

### 4.4 三出口

| 出口 | 含义 | 调用方动作 |
|---|---|---|
| 成功 | 已提交，账已动 | 继续 |
| `INSUFFICIENT` | 确定没扣 | 改发兜底档 |
| `TIMEOUT_UNKNOWN` | 还在缓冲 / 落库中，**大概率会扣** | **不切兜底、不落库、抛可重试**；用户凭同 token 重放（`recordId` 由「用户 + token」确定性映射） |

被否：把 `waitTimeoutMs` 抬到「绝不超时」——卡住的 flush 会把 HTTP 线程一起按 2 秒，且掩盖引擎的真实出口。

### 4.5 兑换确认

- **进合并器**（与预扣同分片），落库是与预扣**同形状的另一组**：`waiting -= c, issued += c`，同一个 `QuotaMergeBuffer`、同一条 `flushGroup` 家族。
- **幂等键仍是 `type = 2` 明细的 `uk_biz_type`**（`type` 列常留），撞键 = 已确认过 = 按成功回放。
- **`waiting` 不足 = 不变量破坏，不是业务失败**：整组回滚（连确认明细一起）+ `error` 日志 + 抛可重试 ⇒ MQ 重投 ⇒ 死信。**不复活 `INVENTORY_WAITING_SHORTAGE(4312)`**，**不做部分确认**。
- 确认只存在于实物段 ⇒ 方法**不带 `segment`**；`invId <= 0`（不限量档 `draw_record.inv_id` 落 `NULL`）先短路为「无事可做」，不与「账不平」共用异常。
- **逐行 `INSERT IGNORE`，不批插**：确认批天然小（一份实物出库），逐行插把「哪几行是新增」的歧义整条删掉；转移量精确等于新增量。
- 预扣与确认**不合成一条三列 `UPDATE`**：预扣的 `surplus` 不足是业务（切兜底），确认的 `waiting` 不足是不变量破坏（要人看），两个失败域不能混在一条语句里。

### 4.6 台账与明细

- **台账三列保留**：预扣直接推进（实物 `surplus→waiting`，虚拟 `surplus→issued`），确认 `waiting→issued`。它们是唯一真相，不再有「Redis 余量面」要对齐。
- **明细表保留、字段精简**：留 `inv_id / quantity / biz_type / biz_id / create_time` + `uk_biz_type (type, biz_type, biz_id)`；删 `state`、`bucket_id`、`idx_pending`、`idx_key_cell`。
- **读数瘦成台账三列**：`InventoryAuditVO` 从 7 字段 → 4（`invId / surplusQty / ledgerWaitingQty / ledgerIssuedQty`）；`pendingDeductQty` / `pendingClaimQty` / `mergedDeductQty` / `ledgerConsistent` / `redisConsistent` 全删；守恒等式降级为集成测试断言（`acceptance.md` B2 / B3）。

## 5 删什么

### 5.1 better-school

| 删 | 位置 |
|---|---|
| `PostCountConsumer` / `ProductCountConsumer` | `bs-trigger/.../trigger/listener/` |
| `PostCountAggregator`（含 100k LRU、`synchronized`、`flush`/`shutdown`） | `bs-infrastructure/.../adapter/repository/post/` |
| `Constants.POST_COUNT_TOPIC` / `PRODUCT_COUNT_TOPIC` | `bs-types/.../types/common/Constants.java` |
| `CommunityRepository` 的 `submitCountMessage` / `saveCountMessage` / `saveViewCountMessage` / `likePostWithCountMessage` / `unlikePostWithCountMessage`（换成新落库方法） | `bs-infrastructure/.../adapter/repository/community/` |
| `PostDetailCache` 里的计数相关投影与 `evict` 调用 | `bs-infrastructure/.../adapter/cache/post/` |

**改**：`ICommunityPostService` / `PostServiceImpl` 的 `likePost` / `unlikePost`（同步等结果）、`ICommunityCommentService.likeComment` / `unlikeComment`（同）、浏览入口（`submitAsync`）；`PostMapper.selectDetail` 拆两段。

### 5.2 marketing

| 删 | 位置 |
|---|---|
| `inventory_bucket` 两张表（实物 / 虚拟）、台账 `redis_qty` 列 | `sql/schema.sql` + D9 DDL |
| `IInventoryBucketPort` / `InventoryBucketPort` / `InventoryBucketVO` / 桶 Lua（`lua/inventory_bucket_deduct.lua`） | 域 / 基础层 |
| `inventory:merge:dedupe:*` 去重 key、`InventoryLuaScriptManager` 的桶脚本 | 基础设施 |
| `InventoryMergeConsumer` 与 `bs-inventory-merge` topic、`InventoryMqConstants` 里的对应常量 | `marketing-trigger` / `marketing-types` |
| `IInventoryMergeService` 的 `backstopOnce` / `mergeKey`、`InventoryMergeServiceImpl`、`MergeBackstopResultVO` / `MergeKeyResultVO` / `InventoryMergeKeyVO` / `InventoryMergeKeyPO` / `InventoryCellAggregatePO` | 域 / 基础层 |
| `InventoryMergeBackstopJob` / `InventoryBucketWarmupJob`（含注册行，时序见 §6） | `marketing-trigger` |
| `InventoryRepository` 的 `mergeCell` / `queryOldestUnmergedCellTime` / `listUnmergedMergeKeys` / `insertClaimDetail` / `queryDeductBucketId` / 4 个 `sum*` / `listBuckets` | 基础设施 |
| 两个 DAO 的 7 个 `sum*`、`selectMergeKeys` / `selectOldestUnmergedCellTime` / `aggregateUnmergedCell` / `flipUnmergedCell` 及虚拟段同构方法 | 基础设施 |
| `InventoryAuditServiceImpl` 的 `checkRedisConsistent` / `ledgerConsistent` / `sumPending*` | 域 |
| `BucketUnavailableException` 及其异常码 | `marketing-types` |

**改**：`InventoryQuotaServiceImpl.preDeduct`（走 `QuotaMergeBuffer.submitAndWait`，删桶调用）、`confirmIssued`（缩成「短路 + 一次提交 + 失败抛出」，删 `queryDeductBucketId` 与 `catch (DuplicateKeyException)`）、`InventoryWarmupServiceImpl`（只留概率表 / 去掉桶预热）；`XxlJobConfig` handler 清单 6 → 4（`outboxScan` / `pointsExpireScan` / `drawCountBucketExpireScan` / `raffleCacheWarmup`）。

`RaffleShippingCase` 与 `IQuotaConfirmPort` **一行不动**。

### 5.3 DDL

- **切换窗口内只做两件事**：`ALTER TABLE <两张明细表> ALTER COLUMN state SET DEFAULT 1`（让新代码不写 `state` 时落 1，回滚后旧代码显式写 0 不会重合历史行）。**不删任何列、不删任何表**。
- **D9 收口才不可逆**：两张明细表 `DROP COLUMN bucket_id, DROP COLUMN state, DROP KEY idx_key_cell, DROP KEY idx_pending`；`DROP TABLE physical_marketing_quota_bucket` / `virtual_inventory_quota_bucket`（**白名单写表名**——同库还有 `points_bucket` / `draw_count_bucket`，那是用户资产）；删 xxl 注册；同步 `schema.sql`。
- **D9 之前 `schema.sql` 写最终形态**（新装库走新 DDL），存量库等 D9 对齐——两者 7 天不一致，这是明知的代价。

### 5.4 topic / 消费组 / 注册

| 项 | 今天 | 换完 |
|---|---|---|
| `bs-post-count` / `bs-post-count-group` | 计数聚合 | 删除 |
| `bs-product-count` / 对应 group | 商品浏览聚合 | 删除 |
| `bs-inventory-merge` / 对应 group | 库存合并 | 删除 |
| xxl `inventoryMerge` | 已删（seed 幂等 `DELETE`） | 保持删除 |
| xxl `inventoryMergeBackstop` / `inventoryBucketWarmup` | 60s 兜底 / 桶预热 | 窗口内 `trigger_status = 0` 停用 → D9 `DELETE` |
| `bs-lottery-drawn`（中奖事件）、outbox 表 | 保留 | **保留**（确认仍由消费者驱动） |

## 6 上线与回滚

### 6.1 顺序

- **不设开关，一个 PR 换掉**：旧路径踩在将被删的列 / 表 / 桶 / 注册上，`merge.enabled=false` 启动即报错，且新逻辑不维护桶 ⇒ 双跑买不到「一次配置回滚」。
- **计数（bs）先切，库存（marketing）后切，单位是进程**：「只让商品浏览先跑一周」不存在——它在同一个 jar 里，会变成双跑。
- **间隔不是一周**：一个日间高峰 + 一次抽样对账（校园墙高峰 20:00–23:00）。排期：D0 切 bs → D1 晚高峰后抽样 → D2 切 marketing → D9 收口。

### 6.2 上线 8 步（窗口内 10–20 分钟停机，两人：执行 + 复核）

| 步 | 动作 | 验证 |
|---|---|---|
| 1 | 备份 + 初值快照【不停机】 | 两个 dump 有 `Dump completed`；台账三列 / `SUM(like_count)` vs `COUNT(post_like)` 粘进 `cutover-<日期>.md` |
| 2 | 停流与**优雅停机**（SIGTERM，**不要 `taskkill /F`**） | 退出码 0；日志有 `@PreDestroy` 收尾；端口不再监听 |
| 3 | 在途收口【A】重算帖子点赞（关系表是真相）/【B】执行 `cutover-catchup.sql` | `SUM(like_count) == COUNT(post_like)`；两条 `state = 0` 计数为 0、`waiting_qty < 0` 为 0 |
| 4 | 对账与清桶【仅 B】逐 inv 比 `Σ桶余量` vs `surplus_qty`，差额**只记不修**，然后 `DEL bs:inventory:*` | `--scan` 再跑为 0；差额行已进发布记录（没差额写 0） |
| 5 | xxl 注册停用【仅 B】`trigger_status = 0` | 两个 handler 都是 0；`inventoryMerge` 已不存在 |
| 6 | 最小 DDL【仅 B】`state SET DEFAULT 1` | `SHOW CREATE TABLE` 两处都是 `DEFAULT 1`；**没删列没删表** |
| 7 | 起新 jar 验启动 | 健康检查 200；无 `Unknown column` / `Table doesn't exist`；handler 4 个；≥2 分钟无「找不到执行器任务」；日志里**没有** `PostCountAggregator` / `bs-inventory-merge` |
| 8 | 抽样验证与观察期（= `acceptance.md` D1–D3） | 库存守恒 SQL 0 行；计数抽样逐行相等；`BUFFER_FULL` 降级 0、`drainFailed` 0；记录 `flush 次数 / 请求数` |

第 1 步的初值快照是**回滚与「三列守恒」的唯一凭据**，必须进发布记录。

### 6.3 回滚（窗口内只做可逆动作，所以回滚不需要反向 DDL）

四条：停新 jar → 回滚 jar → 重灌桶（`rollback-warmup.sh`）→ 放开 xxl 注册。
**回滚形态 ≠ 新形态**：`inventoryMergeBackstop` **必须回来**——回滚形态里消息丢 ⇒ `surplus` 不降 ⇒ 超卖，这正是它当初存在的理由。
**不会触发回滚的三条**（票 09）：启动失败 / 抽样守恒不平 / 第 8 步观测项异常。而「上线 2 小时后发现超卖」在新模型里不可能（守卫与扣减同一事务）。

### 6.4 D9 收口 3 步（不可逆）

删列与索引（在线 INSTANT）→ 删两张桶表（白名单）→ 删 xxl 注册 + 更新 `schema.sql` 与文档。此后不再考虑回滚。

### 6.5 三份脚本与一条人工 SQL

| 产物 | 位置 | 用途 |
|---|---|---|
| `cutover-catchup.sql` | `marketing/sql/cutover/` | 把在途 `state = 0` 明细按旧逻辑合完（含 `type = 2`，否则切换后 `waiting` 永久高挂） |
| `cutover-cleanup.sql` | 同上 | 清桶前的收尾与差额记录 |
| `rollback-warmup.sh` | 同上 | 回滚时重灌桶 |
| 孤儿预扣 SQL（人工，不建 job） | spec / 运维手册 | `physical_marketing_quota_detail` / `virtual_inventory_quota_detail` LEFT JOIN `draw_record` 找 `record_id IS NULL` 的 `type = 1` 行 |

## 7 验收

全部判据在 [`acceptance.md`](acceptance.md)：A 组引擎不变量（A1–A7，两份实现各一份单测，用例名照抄）、B 组库存守恒（B1–B6）、C 组计数精确（C1–C6）、D 组人工抽样（D1–D3 = §6.2 第 8 步）。

**没有吞吐与 p99 判据**（票 10）：`4,079 / 12,267 / 15,356 / 25,563 TPS` 只作讨论锚点；时延上只判「`timedOut == 0`」，不测 p99 数字。

## 8 受影响的测试与文档

### 8.1 测试

处置清单见 `acceptance.md` §4：3 个类整份退休（20 条）、6 个类部分改写或整份重写、7 个类必须保持绿。落地时**先按清单改测试，再改实现**（红 → 绿，避免「改完实现再顺手改断言」）。

### 8.2 文档

| 文件 | 改什么 |
|---|---|
| `backend/marketing/docs/db/inventory.md` | 「合并触发」「对账」两节整段重写（无消息、无桶、无兜底） |
| `backend/marketing/docs/api/lottery.md` | job 清单与「找不到执行器任务」说明（6 → 4 handler） |
| `backend/marketing/docs/db/draw.md` | 对账窗口第 1 条改成「孤儿预扣，无自动读数，见 SQL」 |
| `backend/docs/architecture/marketing-services.md` | 6 handler → 4 |
| `backend/docs/architecture/timeout.md` | 兜底任务相关描述 |
| `CONTEXT.md` | 「库存配额」「预扣」「时间格合并」三个词条仍写着「Redis 只是余量面」「时间格合并」；「奖品类型」里「实物档的兑换确认是异步的」——全部要改写或删除，否则词条指向已删机制 |
| `docs/adr/0013-in-process-row-merge.md` | 新 ADR 已取代 0011 / 0012（两份历史记录不改） |
| `inventory-l2w256/` | 参考件保留，不随本仓库演进 |

## 9 已知窗口与残留风险

1. **孤儿预扣**：预扣提交、`draw_record` 没落库（`RaffleDrawServiceImpl` 不是一个大事务）。方向是「用户没成功但库存已扣」，能自愈（同 token 重试撞 `uk` 回放），只有用户放弃的部分永久挂账。**旧兜底也管不了它**，靠 §6.5 那条人工 SQL。
2. **账不平在生产上零自动信号**：没有 metrics 端点、`alarm_email` 为空；只有死信队列、`draw_record` 停在待发货、人工查台账三条路。
3. **`view_count` 在主表行上**：浏览的合并 `UPDATE` 与「审核通过 / 软删 / 编辑」抢同一把行锁。5ms 窗口把频率压到每帖 ≤ 200 次/秒（约 10% 行占用），可接受；彻底分开要把列挪进窄表——不在本次范围。
4. **搜索索引里的 `likeCount` 不会实时更新**（索引回写按内容变更触发，不按计数）。点赞现在精确，但索引会长期停在旧值——本次接受，改成定期刷是后续事。
5. **参数未扫参**：窗口 / 批 / 分片 / 线程全是模型推导，靠上线后的 `MergeMetrics` 观测调。
6. **单实例假设**：本期部署单实例；多实例要网关按 key 分流（§2.8 的口子已留，实现不在本期）。
7. **两份实现的漂移风险**：从「编译器保证」变成「review 要看见」——靠同批提交 + 同名单测压住（票 06 §9）。

## 10 落地票索引（`../hot-row-merge-l2w256-impl/`）

| 票 | 内容 | 依赖 |
|---|---|---|
| 01 | bs 版合并器 `CountMerger` + `ShardBuffer` + A1–A7 单测 | — |
| 02 | marketing 版合并器 `QuotaMergeBuffer` + A1–A7 单测 | — |
| 03 | bs 计数写路径（点赞 / 取消 / 评论赞 / 浏览）+ 读路径（缓存摘计数）+ C 组断言 | 01 |
| 04 | marketing 库存落库（预扣 / 探再切 / 确认）+ 删桶层 + B 组断言 | 02 |
| 05 | 删兜底 / 读数瘦身 / xxl 注册 / topic 与消费组清理 | 04 |
| 06 | DDL + `schema.sql` + 三份脚本 + 文档同步（§8.2） | 03 / 04 / 05 |
| 07 | 全量回归：按 `acceptance.md` §4 逐条勾 | 06 |

01 与 02 可并行（两工程无 reactor 依赖）；03 排在 01 后、04 排在 02 后；05–07 串行。
