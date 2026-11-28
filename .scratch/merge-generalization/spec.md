# spec · 攒批合并通用化（调用方传落库函数）

> 状态：**已完成**（2026-11-22）。代码主体编译通过（`mvn -o -DskipTests test-compile` → BUILD SUCCESS）；§6 三项收尾已做完；
> 设计已沉淀为 `backend/marketing/docs/architecture/merge.md`。按用户要求未跑测试。
> 本文件 = 本次改造的设计说明；长期文档见上面那份 architecture 文档（本文不再单独维护）。

## 1. 背景

上一轮已把库存合并从 `domain` 迁到 `infrastructure/merge`，但那次只解决了**分层**问题，没有解决**复用**问题：引擎仍然与库存写死在一起。

| 现状（上一轮之后） | 后果 |
|---|---|
| `InventoryMerger` 的入口是 `submitDeduct(InventoryDeductRequestVO)` / `submitClaim(InventoryClaimRequestVO)` | 引擎 `import` 了 4 个库存域类型，换个用途就得复制整包 |
| 落库出口是 `IInventoryFlushHandler`（接口 + 实现 bean） | 每加一种用途就多一个接口、多一个实现类 |
| 分组键写死「动作 × `AwardInventoryTypeEnum` × `awardId`」 | 非库存用途没有 `type` 这一维 |
| 业务翻译（`DEDUCTED/INSUFFICIENT/RETRY_LATER`）与降级混在 `InventoryMergeWriter` | 翻译是业务口径、降级是通用能力，混在一起导致每加用途都要重写降级 |

真正有价值、可复用的只有三件事：**分片路由、攒批窗口、单飞落库 + 五态回填**。其余都是业务方自己的事。

## 2. 目标 / 非目标

### 目标

1. **引擎零业务类型**：件是任意 `T`，结果是任意 `R`，`merge` 包不 import 任何 `domain` 类型。
2. **调用方传函数**：一类任务 = `MergeSpec.of(名字, 分片键, group -> 一组怎么落库)`。
3. **降级通用化**：缓冲满 / 停机中 → 同线程用**同一个落库函数**落组大小 1，这段逻辑只写一次。
4. **一个引擎服务多个用途**：规格随件走，批内按「规格身份 × shardKey」分桶，各调各的函数。
5. **域仍然零感知**：域只见仓储的单件方法，不知道这次扣减有没有合并、有没有降级。

### 非目标

- 不动线程模型、攒批算法、停机三步、背压策略（这些上一轮已验证过，一行不改）。
- 不动任何 SQL、事务边界、配置默认值。
- 不做「不等结果」的异步入口（`submitAsync`）——见 §7 开放问题。
- 不跨工程收敛 better-school 的 count merge（成本大，另开票）。

## 3. 设计

### 3.1 包结构（`marketing-infrastructure/.../infrastructure/merge/`，9 个文件）

| 文件 | 职责 | 是否认识业务 |
|---|---|---|
| `MergeFlush<T,R>` | `List<R> flush(List<T> group)`：一组落一次（一个事务）的**函数**，调用方传 | 否 |
| `MergeSpec<T,R>` | `name`（只进日志）+ `shardKey(item)`（路由键 = 分组键）+ `flush` | 否 |
| `MergeEngine` | 攒批引擎：分片线程、窗口、背压、停机、回填五态（`@Component` + `@PreDestroy`） | 否 |
| `MergeWriter<T,R>` | 通用同步外观：`write(item)` = 入队 → 等 → **接不住就降级** | 否 |
| `MergeConfig` | 引擎参数（`merge.batch.*`，构造即校验） | 否 |
| `MergeState` / `MergeOutcome<R>` | 五态枚举、终态 + 业务结果一体的返回体 | 否 |
| `Pending` / `ShardFlusher` | 缓冲件（规格 + 载荷 + 路由键 + future）、分片常驻线程 | 否 |

业务侧的三个角色固定不变：**落库函数**（一组一事务）、**写入组件**（定义规格 + 翻译业务出口）、**仓储**（一行转发）。

### 3.2 API 形状

```java
// ① 写入组件构造时定义规格（一个动作一个规格，复用同一实例）
this.deductWriter = new MergeWriter<>(engine, MergeSpec.of(
        "inventory-deduct",
        InventoryDeductRequestVO::awardId,                                  // 分片键 + 分组键
        group -> flushService.flushDeductGroup(                             // 落库函数：一组一事务
                group.get(0).type(), group.get(0).awardId(), group)));

// ② 用时只提交一件
MergeOutcome<DeductResultEnum> outcome = deductWriter.write(request);

// ③ 写入组件把引擎终态翻成域的出口
return switch (outcome.state()) {
    case COMPLETED -> outcome.result();
    case TIMEOUT, FAILED -> DeductResultEnum.RETRY_LATER;   // 结果未知 / 确定没落 —— 域侧动作相同
    case BUFFER_FULL, CLOSED -> throw new IllegalStateException("MergeWriter 已降级，不该冒到这里");
};
```

引擎侧只有一句：

```java
public <T, R> MergeOutcome<R> submitAndWait(MergeSpec<T, R> spec, T item)
```

### 3.3 两层职责切分（这是本次最关键的设计决定）

| 层 | 负责 | 不负责 |
|---|---|---|
| `MergeWriter`（通用） | 入队、等结果、`BUFFER_FULL/CLOSED → 同线程落组大小 1` | 业务出口怎么翻译、日志里的业务字段 |
| 写入组件（业务） | 规格定义（分片键、落库函数）、五态 → 域出口翻译、业务日志 | 攒批、降级、"件是不是还在缓冲里" |

上一轮把两者合成一个 `InventoryMergeWriter`，结果是「降级」这段通用逻辑被业务类持有；拆开后新增用途不再需要重写降级。

### 3.4 引擎终态 → 通用层动作 → 域出口（库存为例）

| 引擎终态 | `MergeWriter` 动作 | 域出口 | 调用方动作 |
|---|---|---|---|
| `COMPLETED` | 原样返回 | `DEDUCTED` / `INSUFFICIENT` / `CONFIRMED` | 继续 / 切兜底 |
| `BUFFER_FULL` / `CLOSED` | 本线程用同一函数落组大小 1 → 结果包成 `COMPLETED`；函数抛异常 → `FAILED`；函数没回填 → `TIMEOUT` | 同第一行 | 同上（抽奖不因"合并器忙"丢奖） |
| `TIMEOUT` | 原样（不重试、不落库） | `RETRY_LATER` | 不切兜底、抛可重试、凭 bizId 重放 |
| `FAILED` | 原样 | `RETRY_LATER` | 同上（唯一键兜底不双扣） |

> 「结果未知」与「确定没落」在**引擎态**上必须分开（`TIMEOUT` / `FAILED`），在**域出口**上可以合并（同一个可重试动作）。上一轮把这两个事实也都压成 `RETRY_LATER`，域侧看不出区别；若要区分（日志/埋点），加一个出口值即可，不改通用层。

### 3.5 不变量（与上一轮逐条相同，只换主语）

- **I1 不丢件、恰一终态**：`Pending.complete` 幂等 CAS；漏回填补 `FAILED`。
- **I2 同分片单飞**：每分片恰一条常驻线程，摘批与等待同锁，结构即保证。
- **I3 锁不跨 DB**：临界区只碰内存；落库在锁外。
- **I4 停机三步**：停收 → 排空 → signal + join（`@PreDestroy`）。
- **I5 背压有界**：满即拒（`BUFFER_FULL`），不扩容、不挤掉已入队件。

### 3.6 新旧对照

| 上一轮 | 本次 |
|---|---|
| `InventoryMerger`（构造注入落库接口） | `MergeEngine`（构造只吃 `MergeConfig`） |
| `IInventoryFlushHandler`（2 方法的接口 + 实现 bean） | `MergeFlush<T,R>`（1 方法的函数，调用点传 lambda） |
| `submitDeduct` / `submitClaim` | `submitAndWait(spec, item)` |
| `GroupKey(action, AwardInventoryTypeEnum, awardId)` | `GroupKey(spec 身份, shardKey)` |
| `InventoryMergeWriter`（翻译 + 降级） | `MergeWriter`（只降级）+ `InventoryQuotaMergeWriter`（只翻译） |
| `merge.inventory.*` | `merge.batch.*` |
| 引擎 `close()` 由 `@Bean` destroyMethod 推断 | `@PreDestroy`（引擎自注册为 `@Component`） |

### 3.7 新用途接入样板（三步，不改 merge 包任何文件）

```java
// ① 落库函数已经存在（一组一事务的 SQL，比如 CommunityRepository#flushGroup）
// ② 写一个写入组件
@Component
public class PostLikeMergeWriter {
    private final MergeWriter<PostLikeEvent, LikeStateEnum> writer;

    public PostLikeMergeWriter(MergeEngine engine, CommunityRepository repository) {
        this.writer = new MergeWriter<>(engine, MergeSpec.of(
                "post-like", PostLikeEvent::postId, repository::flushGroup));
    }

    public LikeStateEnum like(PostLikeEvent event) { /* 五态 → 业务出口 */ }
}

// ③ 仓储里一行转发
@Override
public LikeStateEnum like(String postId, String userId) {
    return postLikeMergeWriter.like(new PostLikeEvent(postId, userId));
}
```

### 3.8 装配与配置

- `MergeEngine`：`@Component`，构造参数只有 `MergeConfig`；`@PreDestroy close()`。
- `MergeConfig`：`marketing-app/config/MergeEngineConfig` 从 `merge.batch.*` 读，并在启动时校验
  `shard-count <= hikari.maximum-pool-size - 2`（per-shard 线程 ⇒ flush 并发 = 分片数）。
- 配置默认值不变：`8 / 5ms / 200 / 100ms / 2000 / 5s`。
- 单实例 vs 多实例：现在只有一个引擎 bean；多用途共用一份分片与线程（省线程、热点行集中）。
  需要隔离时按需起第二个实例 —— 但 `@Component` 方式下多实例要改成 `@Bean` + `@Qualifier`，见 §7。

## 4. 迁移影响面（已完成）

| 位置 | 改动 |
|---|---|
| `infrastructure/merge/` | 重写为通用组件（9 文件，删 `InventoryMerger` / `InventoryMergeWriter` / `IInventoryFlushHandler` / `MergeAction`） |
| `infrastructure/inventory/adapter/repository/InventoryQuotaMergeWriter` | 新增：两个规格 + 五态翻译 |
| `.../InventoryQuotaFlushService` | 去掉接口实现，两个组方法就是落库函数（SQL 一行未改） |
| `.../InventoryRepository` | 注入 `InventoryQuotaMergeWriter`，`deduct` / `claim` 一行转发 |
| `marketing-app/config/MergeEngineConfig` | 原 `InventoryMergeConfig`，只产 `MergeConfig` bean |
| `application.yml` | `merge.inventory.*` → `merge.batch.*` |
| `marketing-domain/**` | **零改动**（上一轮已收缩为单件语义，本次不碰） |
| 测试 | `MergeEngineTest` / `RecordingFlush` / `MergeConfigTest`（infra）、`MergeEngineConfigTest`（app）、库存守恒集成测试只换注入类型 |

## 5. 验收标准（DoD）

1. `mvn -o -DskipTests test-compile` 通过 ← **已达成**
2. `MergeEngineTest` 覆盖：A1 并发恰一终态、漏回填补 FAILED、整组异常、A2 单飞、A3 锁不跨 DB、A4 排空/停收/排空超时、A5 满即拒/拒收不伤已入队、A6 批上限 + 同分片保序、规格隔离、业务失败透传、`TIMEOUT` 不等于失败。
3. 库存守恒集成测试 B0–B4 断言语义不变（只把注入点从 `InventoryMerger` 换成 `MergeEngine`）。
4. `grep -rn "InventoryMerger\|InventoryMergeWriter\|IInventoryFlushHandler\|merge\.inventory"` 零命中（`target/` 除外）。
5. `marketing-domain` 对 `infrastructure` 零 import（注释提到路径不算）。
6. **接入新用途不需要改 `merge` 包任何文件**——这是通用化是否成立的唯一判据。

## 6. 收尾（已完成，2026-11-22）

- [x] `MergeState` 类注释「由 `InventoryMergeWriter` 翻成同步落库」→ 已改为 `MergeWriter`（并去掉「库存」主语）。
- [x] `docs/db/inventory.md:99`「写入组件 `InventoryMergeWriter` → 引擎 `InventoryMerger`」→ 已改为
      「`InventoryQuotaMergeWriter` → 通用引擎 `MergeEngine`（`infrastructure/merge`）」，并链到 architecture 文档。
- [x] §3 的设计沉淀为 `backend/marketing/docs/architecture/merge.md`，`docs/db/inventory.md` 与 `docs/README.md` 均已链过去。
      文档内避免出现旧类名 / 旧配置键字面，保持 §5 DoD-4 的 grep 零命中。

## 7. 开放问题（需要拍板）

1. **要不要 `submitAsync`（不等结果）？** 计数类（浏览/曝光）用不上结果，每件分配 future + park/unpark 是纯浪费（better-school 的 `CountMerger` 就为这个留了第二个入口）。加的话是引擎加一个入口 + 一个 `SubmitOutcome` 枚举，不影响现有语义。
2. **一个引擎还是按用途分引擎？** 现在共用一个（省线程、热点行集中）。若某用途落库很慢，会占住分片窗口、拖慢同分片其它用途的件。要隔离就得支持多实例装配（`@Bean` + `@Qualifier` + 各自一份配置前缀）。建议：先共用，真出现互相拖累再拆。
3. **配置键更名 `merge.inventory.*` → `merge.batch.*` 是否可接受？** 本仓策略是"不考虑配置兼容"，但配置中心/部署脚本若有旧键需同步改（本仓 `application.yml` 已同步）。
4. **是否顺手把 better-school 的 count merge 收敛到本组件？** 两工程不同 artifact，需要把它抽成共享模块或复制一份；建议另开票，不混在本次。
