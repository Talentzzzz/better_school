# 进程内合并（攒批）通用组件

> 归属：`bs-infrastructure/.../infrastructure/merge`（2026-11-24 起只有通用引擎六件：MergeConfig / MergeEngine / MergeGroup / MergeItem / MergeResult / MergeStrategy；库存与计数都自己 new 引擎，计数侧的专用 `merge/count` 子包已删除），参数装配在 `bs-app/config` 的 `MergeEngineConfig`（一份 `merge.batch.*`）。
> 本文只写「它为什么长这样、怎么接新用途、哪里不能动」；库存侧口径见 [`../db/inventory.md`](../db/inventory.md)，
> 引擎参数见 `application.yml` 的 `merge.batch.*`。
> 历史：hot-row-merge-l2w256（ADR-0013）把合并从 Redis 桶 + 消息搬进进程；通用化那一轮把引擎与库存解绑。
> 本轮只做收敛：9 个文件 → 4 个，行为一行未改。之后把 `MergeShard`（分片本体）/ `MergeItem`（缓冲里的一件）从 `MergeEngine` 的私有嵌套类
> 提为**包内类**（4 → 6 个文件，行为仍未改）：并发细节单独成篇，引擎只剩路由、分组、回填、降级、停机。
> 再一轮（策略上移）：`MergeEngine` 变 `MergeEngine<T,R>`、构造时绑定唯一策略，`MergeItem` 去掉策略字段——
> 文件数仍是 6，但批内分组从「策略身份 × `shardKey`」降为 `shardKey` 一维，两处 unchecked 强转随之消失。

## 0. 一句话

**引擎只做三件可复用的事——分片路由、攒批窗口、单飞落库 + 五态回填；「怎么落库」由调用方以函数传入。**

件是任意 `T`，结果是任意 `R`，`merge` 包不 import 任何 `domain` 类型。**一类用途 = 一个 `MergeEngine<T,R>` 实例**：
策略 `new MergeStrategy<>(名字, 分片键, group -> 一组怎么落库)` 在引擎构造时注入并定格，件里不带它。

## 1. 包结构：6 个文件，引擎零业务类型

| 文件 | 职责 | 认识业务？ |
| --- | --- | --- |
| `MergeStrategy<T,R>` | 策略：`name`（只进日志）+ `shardKey(item)`（路由键 = 分组键）+ `Flush` 落库函数（**嵌套接口**）——「这一类请求怎么合并」的全部答案 | 否 |
| `MergeEngine<T,R>` | 引擎：入口路由、批内按 `shardKey` 分组、逐件回填、同步降级、停机编排；`commit` 含同步降级。**一个实例绑一个策略**；不是 Spring Bean（写入组件 `new` 它并在 `@PreDestroy` 里 `close`） | 否 |
| `MergeShard` | 合并分片（**包内**）：一条常驻 daemon 线程 + 一个缓冲队列，管攒批窗口、双缓冲换批、满即拒、停机排空。不认识引擎，批交给构造时传入的 `flusher` | 否 |
| `MergeItem<T,R>` | 缓冲里的一件（**包内**）：载荷 + 路由键 + `CompletableFuture`；**不带策略**——引擎只有一个策略。`settle` 用 CAS 保证恰一个终态 | 否 |
| `MergeResult<R>` | 「终态 + 业务结果」一体返回；五态枚举是**嵌套的** `MergeResult.State` | 否 |
| `MergeConfig` | 引擎参数（构造即校验，不可变） | 否 |

只有 `MergeConfig` / `MergeStrategy` / `MergeEngine` / `MergeResult` 四个是**公开类型**（包外可见的全部 API）。
`MergeShard` / `MergeItem` 是**包内可见**（`final class`，无 `public`）：包外看不见，但也不必为了藏起来就塞进
`MergeEngine`——分片的锁、条件变量、双缓冲是独立的一摊并发细节，值得单独一个文件；引擎因此只剩
「路由 → 分组 → 回填 → 降级 → 停机」，两边的修改理由互不相干。

业务侧固定三个角色，**都不在 `merge` 包里**：落库函数（一组一事务）、写入组件（定义策略 + 翻译业务出口）、
仓储（一行转发）。

## 2. API 形状

```java
// ① 写入组件构造时把策略绑到自己的引擎上（一个用途一个引擎，此后策略不再出现在调用点）
this.deductEngine = new MergeEngine<>(mergeConfig, new MergeStrategy<>(
        "inventory-deduct",
        InventoryDeductRequestVO::awardId,                          // 分片键 + 分组键
        group -> flushService.flushDeductGroup(                      // 落库函数：一组一事务
                group.get(0).type(), group.get(0).awardId(), group)));

// ② 用时提交一件：commit = 入队 → 等 → 接不住就降级
MergeResult<DeductResult> outcome = deductEngine.commit(request);

// ③ 写入组件把引擎终态翻成域的出口
return switch (outcome.state()) {
    case COMPLETED -> outcome.result();
    case TIMEOUT, FAILED -> DeductResult.RETRY_LATER;   // 结果未知 / 确定没落 —— 域侧动作相同
    case BUFFER_FULL, CLOSED -> throw new IllegalStateException("引擎已降级，不该冒到这里"); // 只为枚举穷尽
};
```

引擎侧**只有一个入口** `commit(item)`：入队 → 等终态 → 接不住就地同步落库。`BUFFER_FULL` / `CLOSED`
是「件没进缓冲」的入队事实，在 `commit` 里就被降级消化掉，**不外返**；调用方只会看到
`COMPLETED` / `TIMEOUT` / `FAILED` 三态，永远不必自己判断要不要降级。

（内部就是三步：`enqueue` → `await` → 入队失败走 `degrade`、超时 / 中断各自归位。控制流在方法里
一眼看得完，包外则只暴露 `commit` 一个方法。）

**一个引擎一个策略**：批内只按 `shardKey` 分组，不必再按策略对象分桶——不同用途各有自己的引擎、自己的缓冲，
根本不会同批（也就不可能进同一事务）。副产品是 `T` / `R` 由引擎给定，回填、降级、停机都不需要泛型强转。

## 3. 两层职责切分

| 层 | 负责 | 不负责 |
| --- | --- | --- |
| `MergeEngine.commit`（通用） | 入队、等结果、入队失败（满 / 停机）→ 同线程落组大小 1 | 业务出口翻译、业务日志字段 |
| 写入组件（业务） | 策略定义、三态 → 域出口翻译、业务日志 | 攒批、降级、「件还在不在缓冲里」 |

降级是纯通用逻辑，只写一次；新增用途**不需要碰降级**。

## 4. 提交时发生什么 → `commit` 动作 → 域出口（库存为例）

| 提交时发生什么 | `commit` 动作 | 返回 | 域出口 |
| --- | --- | --- | --- |
| 入队成功、落库回填 | 原样返回 | `COMPLETED` | `DEDUCTED` / `INSUFFICIENT` / `CONFIRMED` |
| 入队失败（满 / 停机） | 本线程用同一函数落组大小 1 → 抛异常则 `FAILED`、没回填则 `TIMEOUT` | `COMPLETED`（多数）/ `FAILED` / `TIMEOUT` | 同 `COMPLETED` 行（**抽奖不因合并器忙丢奖**） |
| 等超时（件仍在途） | 不重试、不落库 | `TIMEOUT` | `RETRY_LATER` |
| 落库回滚 / 漏回填 / 停机排空 | 原样 | `FAILED` | `RETRY_LATER` |

两条口径：

- **降级必须复用同一个落库函数**（`strategy.getFlush()`）。另写一套同步版 SQL，两套迟早漂移，而漂移只在压力最大时暴露——
  那正是降级生效的时刻。
- 「结果未知」与「确定没落」在**引擎态**上分开（`TIMEOUT` / `FAILED`），在**域出口**上可以合并（同一个可重试动作）。
  要区分（日志 / 埋点）就在写入组件加一个出口值，**不改通用层**。

## 5. 不变量（改代码前先读这五条）

- **I1 不丢件、恰一终态**：`MergeItem.complete` 幂等 CAS；漏回填（结果短 / 该位 null）补 `FAILED`。
- **I2 同分片单飞**：每分片恰一条常驻线程，摘批与等待同锁，结构即保证（无 `running`/`generation` 标志）。
- **I3 锁不跨 DB**：临界区只碰内存；落库在锁外。用 `ReentrantLock` 而非 `synchronized`（虚拟线程撞 monitor 会钉住载体）。
- **I4 停机三步**：停收 → 排空 → signal + join，顺序不能反（`@PreDestroy`）。连接池销毁晚于本方法，排空时连接仍可用。
- **I5 背压有界**：满即拒（不进缓冲，改由提交线程同步落组大小 1），不扩容、不挤掉已入队件。

## 6. 参数与装配

- 默认值：`8 / 5ms / 200 / 100ms / 2000 / 5s`（`shardCount / maxFlushDelayMillis / maxBatch / waitTimeoutMs / shardCapacity / drainTimeoutMs`）。
- 引擎自身校验（构造即炸）：`shardCount` 是 2 的幂、`waitTimeoutMs > maxFlushDelayMillis`、`shardCapacity >= maxBatch`。
- **装配层校验**：`shardCount <= hikari.maximum-pool-size - 2`。per-shard 常驻线程 ⇒ 单引擎 flush 并发 = 分片数；
  连接池属于应用层，引擎不认识 Hikari，所以这条卡在 `MergeEngineConfig` 的启动路径上。
- **引擎实例数要自己数**：总 flush 并发 = 引擎数 × `shardCount`，上面那条只保证单引擎不越界。
  库存域现在两个引擎（预扣 / 确认），默认参数下是 16 条常驻线程对 8 个连接额度——要么把 `shardCount` 减半，
  要么承认 flush 会在 `connection-timeout` 上排队。
- 分片只决定**路由桶**，合并粒度由 `shardKey` 分组决定，与分片数无关。同一 `shardKey` 恒落同一分片、同一组、同一事务。

## 7. 接入新用途：三步，不改 `merge` 包任何文件

```java
// ① 落库函数已经存在（一组一事务的 SQL，比如 CommunityRepository#flushGroup）
// ② 写一个写入组件：引擎连策略一起 new，收口也归它
@Component
public class PostLikeMergeWriter {
    private final MergeEngine<PostLikeEvent, LikeState> engine;

    public PostLikeMergeWriter(MergeConfig mergeConfig, CommunityRepository repository) {
        this.engine = new MergeEngine<>(mergeConfig,
                new MergeStrategy<>("post-like", PostLikeEvent::postId, repository::flushGroup));
    }

    public LikeState like(PostLikeEvent event) { /* 五态 → 业务出口 */ }

    @PreDestroy
    public void close() { engine.close(); }
}

// ③ 仓储里一行转发
@Override
public LikeState like(String postId, String userId) {
    return postLikeMergeWriter.like(new PostLikeEvent(postId, userId));
}
```

**判据：接入新用途需要改 `merge` 包 = 通用化没成立。** 域永远只见仓储的单件方法，
不知道这一件是并进一批落的还是单件落的。

## 8. 回归防线

- `MergeEngineTest`（infra）+ `RecordingFlush`：A1 并发恰一终态 / 漏回填补 FAILED / 整组异常、
  A2 单飞、A3 锁不跨 DB、A4 排空 / 停收 / 排空超时、A5 满即拒 / 拒收不伤已入队、A6 批上限 + 同分片保序、
  策略隔离、业务失败透传、`TIMEOUT` 不等于失败。
- `MergeConfigTest`（infra）、`MergeEngineConfigTest`（app，含「池 − 2」那条）。
- `InventoryQuotaConservationIntegrationTest` B0–B4：库存守恒语义不变，只换注入点。
- 静态判据：`infrastructure/merge` 不出现旧引擎 / 旧写入组件 / 旧降级接口的类名，配置键只有 `merge.batch.*` 一个前缀；
  `marketing-domain` 对 `infrastructure` 零 import。

## 9. 开放问题（未拍板，改动前先看）

1. **要不要 `submitAsync`（不等结果）？** 计数类（浏览 / 曝光）用不上结果，每件分配 future + park/unpark 是纯浪费
   （better-school 的 `CountMerger` 就为此留了第二个入口）。加的话 = 引擎加一个入口 + 一个 `SubmitOutcome` 枚举，
   不触碰现有语义。
2. ~~一个引擎还是按用途分引擎？~~ **已定：按用途分引擎**——一个业务一个合并器，策略在构造时绑定、件里不带它。
   库存域的预扣与确认已是两个引擎（两条落库函数、两个失败域）。代价是线程数与 flush 并发随引擎数线性增长，
   见 §6「引擎实例数要自己数」。
3. **配置键从「按域命名」改成 `merge.batch.*` 的兼容性**：本仓策略是不考虑配置兼容；配置中心 / 部署脚本若有旧键需同步改
   （本仓 `application.yml` 已同步）。
4. **better-school 的 count merge 是否收敛到本组件？** 两个工程不同 artifact，要抽共享模块或复制一份；建议另开票。
