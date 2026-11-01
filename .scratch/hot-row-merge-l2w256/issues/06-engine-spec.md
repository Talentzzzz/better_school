# 06 · merge-engine 构件的形态与不变量

Type: grilling
Status: resolved
Blocked by: —

## Question

~~两个工程共用一份独立构件（无 Spring）~~ → 改为**两个工程各自在自己的 Spring Boot 里实现一份**（见 `## Comments`）。要定的是它的**对外形状**与**必须被单测锁死的不变量**：

1. **API 形状**：`submit(key, payload, waitMode)` 一类的方法？三类调用方差别很大——计数是 fire-and-forget（不等结果、满了就同步落库），库存是一定要等结果（等 future）。是做成两个入口（`submitAsync` / `submitAndWait`），还是同一个入口带 `waitTimeout=0` 表示不等？
2. **批内混合 key**：参考工程按 `shardKey & mask` 路由，同批可能混多个 key（哈希碰撞），落库时再按 key 分组。计数场景一秒钟可能有上万个不同帖子被赞（长尾），**分片键取什么**（postId？还是 postId 再哈希？），批内混合是常态而不是例外——分组成本与「一条 UPDATE 只碰一行」的收益要重新算。
3. **参数与硬约束**：`shardCount`（2 的幂）、`windowMs`、`maxBatch`、`shardCapacity`、`flushThreads == poolSize`、`setRemoveOnCancelPolicy(true)`。哪些照抄、哪些按计数场景改？给出两个场景各一组默认值（计数 / 库存）。
4. **不变量清单**：单飞（`running` + `generation` 双保险）、`scheduled == false ⇒ 缓冲空`、锁不跨 DB、停机「停收 → 排空 → 关池」、排空失败显式回填。哪些必须原样保留、哪些在计数场景可以省（计数不需要回填，只需要计数丢弃量）。
5. **背压语义**：库存满了 → `REJECTED`（明确没扣）；计数满了 → 不能拒绝用户的点赞（点赞本身已落库），只能**退化成同步单条 UPDATE** 或**丢弃并计数**。选哪个？
6. **多实例口子**：`shardKey` 由调用方给（引擎不参与路由），这样将来网关按 key 哈希分流即可，引擎不用改——这个边界划得住吗？

答案要能直接写成一个 `merge-engine` 模块的 `public` API 清单 + 构造参数表 + 5 条不变量。

## Comments

- 票 10 已划出范围（不做压测）：参数**没有扫参依据**，按参考工程模型推导给默认值，并在 spec 里注明「未扫参，线上按观测调」；引擎的正确性由单测锁（不丢件 / 单飞 / 背压 / 停机排空），不靠吞吐数字证明。
- **2026-10-22 修正（推翻提问里的前提）**：提问把形态定成了「独立构件、无 Spring、两个工程共用一份」，讨论时按这个前提写出了第三种 reactor、泛型 `BatchFlusher` 回调、以及一套「引擎不知道事务」的边界。用户当场否掉：**不要共享构件，两个工程各自在自己的 Spring Boot 里实现**。
  - 改动：删掉 `backend/merge-engine/` 独立模块与构建顺序约束；`BatchFlusher` / `Submission<T>` 这套跨工程 SPI 取消，换成各自工程内的 `@Component` + 包内 `ShardBuffer`；库存那份不写 `submitAsync`（没有 fire-and-forget 的调用方，不为对称造方法）；`@Transactional` 回到合并器自己的 `flushGroup` 上。
  - 不变（这些都是结论，不是包装）：两个入口的形状（等 / 不等）、分片键取行主键、`(key ^ key>>>32) & mask`、两套参数、`flushThreads ≤ poolSize − 2`、5 条不变量、背压不返回 `REJECTED`、多实例边界。
  - 新增的代价：两份代码要靠规范对齐（§9 的三条），漂移风险从「编译期不存在」变成「review 期要看见」。

## Answer

（2026-10-22 定；同日第二次修正：**不共享构件，两个工程各自在自己的 Spring Boot 里实现一份**，见 `## Comments`）

**一句话：每个工程在自己工程里实现一份合并器；要等结果的走 `submitAndWait`，不等结果的走 `submitAsync`（marketing 只有前者，不为对称造方法）；合并器只做「收件 / 攒批 / 单飞 / 背压 / 排空」，分组、SQL、幂等、降级留在自己的仓储与 case 层。两份实现必须逐条对齐的只有两样：算法与 5 条不变量。**

### 0. 边界：合并器不知道什么

先把「不归合并器管」的列清，后面的 API 形状才站得住：

| 不归合并器 | 归谁 | 理由 |
|---|---|---|
| 业务键 → 分片键的推导 | 调用方（显式参数） | 合并器不解析 payload，否则将来网关按 key 分流时两层路由会分叉 |
| 批内分组（同一 flush 里多个 key） | 本工程的落库方法 | 分组形态随业务不同（点赞是「帖 × 用户表」，浏览只是 +1） |
| SQL、幂等（唯一键） | 本工程仓储 | 票 02 / 05 已定：唯一裁决者是 `uk`，不是内存结构 |
| 缓冲满 / 停机的**降级动作** | 调用方（case 层） | 合并器只报 `BUFFER_FULL` / `CLOSED`，不替你决定「降级还是丢弃」 |
| 缓存失效、metrics 上报端点 | 本工程 | 票 03：详情缓存不存计数 ⇒ 合并器不需要 evict 回调 |

**事务边界在合并器自己的 `flush` 方法上**（`@Transactional` 打在「一组一事务」的那个方法上）——这是「各自在 Spring Boot 里实现」带来的最实质差别：合并器可以在自己工程里直接用声明式事务，不必把事务倒推给一个无框架的回调。它仍然**不知道 SQL**（调本工程仓储），也仍然**不做幂等**（唯一键裁决）。

### 1. API 形状：两个入口，不是 `waitMode` 参数

**否决 `submit(key, payload, waitMode)`**：两条路要返回的东西根本不同——等的人要「结果对象」，不等的人连 `future` 都不该分配（浏览量 10k/s，就是每秒一万个 `CompletableFuture` 加一次 park/unpark）。塞进一个签名只有两种下场：要么让不等的人也造一个 `future`（白花），要么返回一个 `null` 让调用方自己分叉（漏判就 NPE）。

两份实现各自只写自己需要的入口——**不给对称性造一个用不到的方法**：

```java
// ── better-school：计数侧（点赞 / 取消赞 / 评论点赞 / 浏览）
//    包 com.betterschool.infrastructure.adapter.merge
@Component
public class CountMerger {

    /** 等结果：点赞 / 取消赞 / 评论点赞。TIMEOUT 时仍可能落库（口径见 §4） */
    public MergeOutcome<LikeState> submitAndWait(long shardKey, CountEvent event);

    /** 不等结果：浏览。非 ACCEPTED 由 case 层降级，合并器不返回 REJECTED */
    public SubmitOutcome submitAsync(long shardKey, CountEvent event);

    public int pending();
    public boolean awaitIdle(long timeoutMs);
    public MergeMetrics metrics();
    @PreDestroy public void close();          // 停收 → 排空 → 关池
}

public enum CountAction { POST_LIKE, POST_UNLIKE, COMMENT_LIKE, POST_VIEW, PRODUCT_VIEW }
public record CountEvent(CountAction action, long targetId, long userId) { }
public enum SubmitOutcome { ACCEPTED, BUFFER_FULL, CLOSED }
public enum MergeState { COMPLETED, TIMEOUT, BUFFER_FULL, CLOSED, FAILED }
public record MergeOutcome<T>(MergeState state, T result) { }
public record MergeMetrics(int pending, long accepted, long flushed, long batches,
                           long rejectedFull, long rejectedClosed, long timedOut,
                           long failed, long drainedFailed, long maxFlushNanos) { }

// ── marketing：库存侧（配额预扣）。只有等结果这一个入口——没有 fire-and-forget 的调用方
//    包 com.betterschool.marketing.infrastructure.inventory.merge
@Component
public class QuotaMergeBuffer {

    /** 等结果；返回值沿用票 05 的三出口（成功 / INSUFFICIENT / TIMEOUT_UNKNOWN），枚举名以票 05 落地为准 */
    public DeductResult submitAndWait(long invId, InventoryDeductRequestVO request);

    public int pending();
    public boolean awaitIdle(long timeoutMs);
    public MergeMetrics metrics();
    @PreDestroy public void close();
}
```

包内各有一个 `ShardBuffer`（不 `public`）。**两份的类名可以不同，但 `ShardBuffer` 的方法名、`MergeMetrics` 的字段名、5 条不变量、参数名与默认值必须一样**——见 §9 的对齐办法。

**better-school 内是一个实例，不是两个。** 点赞（等）与浏览（不等）共用同一份分片与同一个执行池，`CountEvent` 自带动作类型，落库方法在批内按「动作类型 × shardKey」二次分组。理由：两个实例要 2×256 个缓冲区和 2×线程，而它们打的是同一批热点帖（`post_like_count` / `post.view_count`），单飞在同一个分片上反而更稳。marketing 侧只有库存一件事，一个实例一个入口。

### 2. 分片键与批内混合

**分片键 = 这一批要更新的那一行的主键**，不是 payload 再哈希：

| 场景 | shardKey | 打的行 |
|---|---|---|
| 帖子点赞 / 取消赞 | `postId` | `post_like_count` 一行 + `post_like` 一批 |
| 评论点赞 | `commentId` | `post_comment.like_count` 一行 |
| 帖子浏览 / 商品浏览 | `postId` / `productId` | `post.view_count` / `product.view_count`（**在主表行上**） |
| 库存预扣 | `invId` | `*_inventory_ledger` 一行（实物 / 虚拟两段共用 key，撞片无害） |

**路由函数改成高位折叠，不照抄 `key & mask`**：

```java
int shardOf(long key) { return (int) ((key ^ (key >>> 32)) & mask()); }
```

`key < 2^32` 时它**等价于** `key & mask`（本仓库的 `post_id` / `product_id` 都是 `post_id = id` 回写的稠密序列、`inv_id` 是配置里给的两位数，行为与参考工程一模一样）；但雪花 ID、`2^k` 步长的分表拼接 ID 不会全部挤到 0 号片。一次移位 + 一次异或，没有第二个哈希函数要同步。**这一层是合并器内部实现，不外泄**——将来网关按业务键分流用的是它自己的哈希，两层互不耦合（§6）。

**批内混合是常态，但分组成本可以忽略**：一批 ≤ 200 件，`LinkedHashMap` 分组是纳秒级；参考工程已经有 `isSingleSku` 线性快路径（200 次 `long` 比较即可判定单热点，省掉一次 Map 分配）。**落库方法按「一组一个事务」写**——不是「一批一个事务」：组间顺序由首个到达者决定，一组一事务让锁集最小、失败只回滚一组、能精确回填到件；一批一事务会让两个分片以不同顺序锁同两行，多出一个死锁面。「一组一事务」正好是合并器里一个标了 `@Transactional` 的包内方法（票 02 的 `INSERT IGNORE` + 一条 `UPDATE` 就在它里面）。

**长尾的账要诚实算，不能拿热点数字冒充**：

- 单热点（一帖被打爆）：5ms 窗口下批大小随速率涨，单次 flush ≈ 0.5ms + 0.04ms × 行数（参考工程 200 行 ≈ 8ms 实测反推）⇒ 单帖吞吐 ≈ `n / (0.5 + 0.04n)`，上界约 25k 次/秒，5k/s 时批≈25 件、合并比 25:1。
- 长尾（1 万帖/秒各被赞一次）：256 片 ⇒ 每片约 39 次/秒，5ms 窗口 ⇒ 批大小 ≈ 0.2 ⇒ **合并比 ≈ 1，合并器在这条路径上只多一次排队，是净成本**。
- 结论：**合并器的收益来自「同一行的并发」，长尾下它退化成一条多绕 5ms 的直写**。计数侧仍统一走合并器，是因为热度不可预测（帖子随时会爆），统一一条路比按热度分叉两条路便宜；代价是长尾请求多 5ms 延迟——用户看不到（点赞请求返回后前端才刷数，票 03 的读路径是每次查库）。

### 3. 构造参数表：两套默认值

`MergeConfig` 不可变、构造即校验（配置错了要在启动瞬间炸，不是跑到一半每个请求都超时）。

| 参数 | 硬约束 | 计数默认 | 库存默认 | 依据 / 与参考工程的差异 |
|---|---|---|---|---|
| `shardCount` | 2 的幂，且 ≥ 峰值并发热点数 | **256** | **64** | 分片只买「批纯度」（不同热点不同片），不买并行度——并行度由 `flushThreads` 定。计数热点是「几十个帖子同时在爆」，256 够；库存热点是「一次活动 1~3 个 invId」，64 够且省调度 |
| `windowMs` | ≥ 0 | **5** | **5** | 票 03 已定，一套参数 |
| `maxBatch` | > 0 | **200** | **200** | 200 行 ≈ 8ms；再大 `waitTimeout` 预算就吃紧（参考工程默认值） |
| `waitTimeoutMs` | > `windowMs` | **100** | **100** | 5ms 窗口 + 8ms 落库 + 抖动；超时语义两边不同（§5） |
| `shardCapacity` | ≥ `maxBatch` | **5000** | **2000** | 只是「降级触发点 + 内存上界」。库存等结果且资金敏感 ⇒ 宁可更早降级为同步单条，队列别长；256 × 5000 引用 ≈ 5MB 上限 |
| `flushThreads` | 1 ≤ n ≤ `poolSize − 2` | **4** | **2** | **这一条必须改**，见下 |
| `drainTimeoutMs` | > 0 | **5000** | **5000** | 照抄 |
| ~~`soldoutShortcut`~~ | — | **删** | **删** | 库存专属（票 05 的「扣不动再探再切」），属落库方法，不进合并器 |
| `scheduler.setRemoveOnCancelPolicy(true)` | 必须 true | true | true | 照抄：攒满提前触发每秒 cancel 上千个定时任务，不设这个策略它们会躺在延时队列里等原定窗口 |

**`flushThreads == poolSize` 必须改写**，这是本票唯一要推翻的参考工程约束。参考工程是一个只做扣减的独立服务（池 20、线程 20）；本仓库的池是**共享的**：`better-school` 与 `marketing` 的 `hikari.maximum-pool-size` 都是 **10**，且开了虚拟线程（`spring.threads.virtual.enabled: true`），**连接池就是真并发上限**。搬成 `flushThreads = 10` 的后果不是「更快」，是 flush 把 10 条连接全占满、普通查询在 `connection-timeout: 3000` 上排队三秒。

所以这条约束**不写进合并器**：`MergeConfig` 只校验 `flushThreads ≥ 1`，池大小是宿主工程的事实——校验写在各工程建实例的那个 `@Configuration` 里（`flushThreads ≤ hikari.maximum-pool-size − 2`），并把两个数一起打进启动日志。取值逻辑同理改写——**真正的约束是「别把前台连接吃光」，不是「吃满池」**；而单热点单飞意味着 1 条线程就够打爆一个热点，多出来的线程只服务「多个热点同时爆」：库存热点少 ⇒ 2，计数可能几十个帖子同时爆 ⇒ 4。

> **未扫参声明**：以上全部是模型推导（票 10 已把压测划出范围）。窗口/批大小/分片数/线程数**没有实测依据**，上线后按 `MergeMetrics` 的 `batches ÷ accepted`（合并比）与 `timedOut / rejectedFull` 调；调参不改代码结构。

### 4. 背压与降级：没有一个场景把 `REJECTED` 返回给用户

合并器只会说三句：`ACCEPTED` / `BUFFER_FULL` / `CLOSED`。**降级动作全部在 case 层**（票 02 / 05 已定，这里只把它落成表）：

| 场景 | 缓冲满 | 停机中（`CLOSED`） | 等超时（`TIMEOUT`） | 用户看到 |
|---|---|---|---|---|
| 点赞 / 取消赞 | 该请求**同步走同一个落库方法**（组大小=1） | 同左 | 回 `RETRYABLE` | 成功 / 「请重试」（唯一键幂等，重试不会重复计） |
| 评论点赞 | 同上 | 同上 | 同上 | 同上 |
| 帖子 / 商品浏览 | 一次独立 `UPDATE … + 1`；再失败 ⇒ 丢弃并 `droppedViews++` | 丢弃并计数 | 不回填（本来就没等） | 无感知（前端不渲染浏览结果） |
| 库存预扣 | 调用方线程同步扣一批（组大小=1） | 同左 | `TIMEOUT_UNKNOWN`（票 05） | 「处理中」，按票 07 接 |

两条硬要求：

1. **降级必须复用同一个落库方法，不许另写一条 SQL**。合并路径与降级路径共用 `flushGroup(...)`，只是批大小 200 与 1 的差别。否则两套 SQL 迟早语义漂移（`GREATEST(0, …)` 加不加、`INSERT IGNORE` 还是 `INSERT`），而漂移只在压力最大的时候暴露。
2. **丢弃必须计数**。计数的语义是「至多一次」（票 02），但「丢了多少」不能靠日志猜：`MergeMetrics.failed` / `drainFailed` + case 层自己的 `droppedViews`。

### 5. 5 条不变量（全部原样保留，单测逐条锁死）

| # | 不变量 | 参考工程的做法 | 计数场景 | 锁它的单测（两个工程各写一份） |
|---|---|---|---|---|
| **I1** | **不丢件、恰一个终态**：`ACCEPTED` 的件必被落库方法看到一次；每件至多被回填一次（`Pending.complete` 幂等）。落库方法漏回填时，合并器在 flush 返回后补 `FAILED` | 落库方法负责全部回填，合并器不兜 | **保留，且更必要**：点赞的「成功 = 已提交」，漏回填就是用户永远等不到结果 | `everyAcceptedItemGetsExactlyOneTerminalState`：8 线程 × N 件，断言终态计数 = N，且无重复 |
| **I2** | **单飞**：同一分片任一时刻至多一条 flush 体在执行（`running` 标志 + `generation` 代号双保险）。推论：`scheduled == false ⟹ 缓冲区空且无在途 flush`（`awaitIdle` 的无锁判据） | 已是此形态，注释里写明「`Future.cancel()` 对运行中的任务也返回 true，不能用它判单飞」 | 原样保留 | `singleFlightNeverTwoConcurrentFlushes`：并发提交 + 阻塞落库，断言最大并发度 == 1、总数一件不少 |
| **I3** | **锁不跨 DB**：临界区内只有内存操作，落库方法在锁外调用 | 已是此形态；锁用 `ReentrantLock`（虚拟线程阻塞在 `synchronized` 上会 pin 载体线程） | 原样保留（两个工程都开了 VT，pinning 风险更高） | `flushBlockingDoesNotBlockSubmit`：落库阻塞 300ms 期间，`submitAsync` 仍在毫秒级返回 |
| **I4** | **停机三步：停收 → 排空 → 关池**，顺序不能反；排空超时则把剩余件**显式回填**为失败 | `destroy()`：`accepting=false` → `awaitIdle(drain)` → `failRemaining` → `scheduler.shutdown()` | **回填的对象可以省，回填这件事不能省**：浏览件没有 future（`complete` 是空操作），但必须计入 `drainFailed`；点赞件必须回填成 `FAILED`，让用户拿到「可重试」而不是干等 100ms | `closeStillDrainsBufferedItems` / `closeRejectsNewSubmissions` / `drainFailureIsCounted` |
| **I5** | **背压有界、不静默丢**：分片满 ⇒ `offer` 立刻返回 false，不阻塞、不扩容、不挤掉已入队的件；**合并器自己永不丢弃已入队的件**（丢弃只发生在 case 层的降级分支，且必须计数） | `pending.size() >= capacity` 即拒绝 | 原样保留（这是「不许把 `REJECTED` 给用户」能成立的前提：拒绝发生在入队之前，调用方还来得及同步写） | `rejectsWhenFullWithoutTouchingQueue` / `acceptedItemsSurviveRejection` |

**计数场景真能省的只有两处**，都不动不变量本身：

- 浏览（`submitAsync`）不需要 `CompletableFuture`，也不需要结果对象 ⇒ `Pending` 的 future 为 `null`，`complete()` 是空操作。省的是每件一次堆分配 + 一次 park/unpark，不是省 I1/I4。
- 计数不需要「排空失败后把增量并回窗口重试」（票 02 已否决 at-least-once）⇒ `drainFailed` 只是读数，不回补。

**合并器另外两条不写成「不变量」但同样要断言的**：单批 ≤ `maxBatch`；同分片内保持入队顺序（跨分片不保证）。

### 6. 多实例口子：划得住，但有三个前提

结论：**划得住**。`shardKey` 是显式参数、合并器只做「mix + mask」，将来网关按业务键哈希分流时，两份实现都是一行不改——实例选择在网关、片内选择在合并器，两层是独立的映射，不共享哈希函数。

三个前提必须写进 spec，否则这个边界会在落地时被悄悄破掉：

1. **合并器不得从 payload 推导 key**。一旦合并器自己取 `event.postId`，网关那层就没法按同一个 key 分流了——两层路由会分叉。
2. **同一个业务行在所有调用点必须给同一个 `shardKey`**。点赞和浏览都用 `postId`（哪怕它们打的是两行）。这条单测锁不住，靠一条代码约束：分片键只从「行主键」取，不从「动作」取。
3. **网关分流的方向是「同 key 同实例」，不是「均匀分布」**。热点帖的点赞会被集中到一个实例上——这正是合并生效的前提，不是缺陷；要接受「单帖热度的上限 = 单实例单分片的 flush 能力」。

代价也要写明：多实例后每个实例都有自己的缓冲，**崩溃丢失的窗口按实例计**，但点赞是「成功 = 已提交」（进程崩了调用方拿不到成功），所以不产生「以为成功其实没落」的缺口；浏览本来允许丢。

### 7. 观测（同时回答 map 里「观测口径」那条雾）

`MergeMetrics` 只给快照 record，合并器不依赖 micrometer、不开端点——各自工程的 actuator / 日志 / 票 08 的对账 job 去读。要有的字段与用途：

| 字段 | 回答什么问题 |
|---|---|
| `pending` | 现在压了多少（背压预警） |
| `accepted / flushed` | 丢没丢件（差值应恒为 0，除非进程崩溃） |
| `batches` | **合并比 = `flushed / batches`**，调 `windowMs` / `maxBatch` / `shardCount` 的唯一依据 |
| `rejectedFull / rejectedClosed` | 降级发生了多少次（不是错误，是容量信号） |
| `timedOut` | `waitTimeoutMs` 够不够（库存侧等于 `TIMEOUT_UNKNOWN` 率） |
| `failed / drainedFailed` | 落库异常 + 停机丢弃 |
| `maxFlushNanos` | 单次 flush 最坏耗时，对照 `waitTimeoutMs` 看预算 |

### 8. 明确不做（边界清单）

- 不做业务键路由、不做分布式分配、不做跨实例协调（§6）。
- 不做幂等（唯一键是唯一裁决者）、不重试（`TIMEOUT` 交给 case 层决定重试与否）。
- 不做分组、不做拆批、不做售罄短路（属落库方法）。
- 不碰缓存、不发事件、不写 outbox。
- 不回补丢弃量（至多一次，票 02）。

### 9. 落地形态：每个工程一份，各自在自己工程里

**不做独立构件，不建第三个 reactor，两家不互相依赖。** 两份实现落在各自工程的 infrastructure 层，跟着各自的 Spring 上下文走：

| | better-school | marketing |
|---|---|---|
| 包 | `com.betterschool.infrastructure.adapter.merge` | `com.betterschool.marketing.infrastructure.inventory.merge` |
| 组件 | `CountMerger`（`@Component`，计数侧唯一实例） | `QuotaMergeBuffer`（`@Component`） |
| 包内 | `ShardBuffer`（不 `public`）、`Pending` | 同左 |
| 入口 | `submitAndWait`（点赞类）+ `submitAsync`（浏览） | 只有 `submitAndWait` |
| 落库方法 | `@Transactional` 的 `flushGroup(action, key, group)` → `CommunityRepository` / `PostMapper` | `@Transactional` 的 `flushGroup(invId, group)` → `InventoryRepository` |
| 停机 | `@PreDestroy close()`（`HikariDataSource` 的销毁晚于它，逆序天然满足；写一句注释锁住） | 同左 |

**这样做的三处收益**：一是不用为「无框架」付成本（`@Transactional`、`@PreDestroy`、配置绑定都能直接用）；二是两个工程各只写自己需要的入口（marketing 那份没有 `submitAsync`，也不该为了对称造一个）；三是构建只有一条命令，不存在「先 install 一个共享构件、否则编译不过」这种新机器上的第一道坎。

**代价是两份代码，必须靠规范而不是靠编译器对齐。** 三件事照做即可把漂移压到「改动时能被 review 看见」：

1. **只对齐「算法与契约」，不对齐「代码长相」**：`ShardBuffer` 的字段语义（`scheduled` / `running` / `generation` / `size` / `closed`）、方法名（`offer` / `runFlush` / `bringForward` / `failRemaining` / `isIdle`）、`MergeMetrics` 字段名、参数名与默认值（§3 的表）两边一模一样。
2. **同一组 5 条不变量单测各写一份**，用例名也照 §5 抄。谁改坏单飞，自己工程先红，不需要等另一个工程。
3. **改动要在两边同一个 PR / 同一批提交里落**。这条是纪律不是机制——所以要写进落地 spec 的「改动检查表」，而不是指望 CI。

参考工程的那四个类（`StockMergeServiceImpl` / `ShardBuffer` / `MergedDeductExecutor` / `MergeConfigVO`）照抄时要去掉的三样：`@Service` 与 `DisposableBean`（换 `@Component` + `@PreDestroy`）、`skuId` 与 `DeductRequestVO`（换各自工程的类型）、`soldoutShortcut`（票 05 的「扣不动再探再切」在落库方法里，不在缓冲层）。

### 10. 遗留风险（不在本票范围，落地时要看一眼）

`post.view_count` 与 `product.view_count` 是**主表列**，浏览合并 UPDATE 打的是内容/状态那同一行。5ms 窗口把写入频率压到每帖 ≤ 200 次/秒（约 10% 行占用），可接受；但它与「审核通过 / 软删 / 编辑」抢的是同一把行锁。真要彻底分开，得把 `view_count` 挪进窄表——那是表结构变更，不在本次范围（map 的 out-of-scope 已列「点赞关系表结构不动」，浏览量列同理）。
