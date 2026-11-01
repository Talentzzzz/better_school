# 02: marketing 版合并器（`QuotaMergeBuffer` + 同名单测）

**Spec:** `.scratch/hot-row-merge-l2w256/spec.md` §2.1–§2.8、§4.1；判据见 `acceptance.md` §2 A 组

**What to build:** 在 marketing 侧独立实现一份合并器——与 01 **不共享构件、不互相依赖**，各在自己的 Spring Boot 里跟着自己的 `@Component` / `@Transactional` / `@PreDestroy` 走。对齐的是「算法 + 五条不变量 + 参数名 + 方法名 + 用例名」，不是代码长相。

与 01 的差异只有两处：参数不同（`shardCount 64 / shardCapacity 2000 / flushThreads 2`，其余同值），且**只有 `submitAndWait`——没有 fire-and-forget 的调用方，不为对称造方法**。

本票交付引擎骨架 + 建实例配置；落库方法先由测试替身顶上，两个真身（预扣 / 兑换确认）分别由 06 / 07 落，且必须是两个各自 `@Transactional` 的组方法，不合成一个。

**Blocked by:** None (can start immediately) —— 与 01 并行，两工程无 reactor 依赖

**Status:** resolved

- [x] A1–A7 单测全绿，用例名与 01 逐字一致（两份实现各自在自己工程里先红，是「漂移能被自己发现」的唯一防线）
- [x] 公共形状与 01 对齐：`ShardBuffer` 的 `offer / runFlush / bringForward / failRemaining / isIdle`、`Pending`、`MergeMetrics` 字段名逐字相同，便于对照调参
- [x] 五条不变量各有一条断言（同 01 的口径，含「不用 `Future.cancel()` 判单飞」与「临界区不碰 DB」）
- [x] `MergeConfigTest` 四条负例齐（`shardCount` 非 2 的幂 / `waitTimeoutMs ≤ windowMs` / `shardCapacity < maxBatch` / `flushThreads < 1`）
- [x] 参数默认值 `64 / 5ms / 200 / 100ms / 2000 / 2 线程`；`shardCapacity` 更小是刻意的——库存资金敏感，更早降级
- [x] 分片路由同样用高位折叠；`invId` 是两位配置但折叠不省
- [x] `flushThreads ≤ poolSize − 2` 在配置类校验，并与连接池上限一起打进启动日志
- [x] 引擎对落库方法只要求「一组一事务」的组方法形状，不假设组内 SQL；替身落库能让 A1–A6 全绿
- [x] `@PreDestroy close()` 三步到位；停机后排空失败计入 `drainedFailed` 而不是静默丢

## 落地记录（06 / 07 接线时的接口）

包：`com.betterschool.marketing.infrastructure.inventory.merge`（引擎）+ `domain.inventory.model.valobj`（对外类型）。

```java
public class QuotaMergeBuffer {                                  // 普通类：06 接上落库方法时再补 @Component
    DeductResult submitAndWait(long invId, InventoryDeductRequestVO request);
    ClaimOutcome submitAndWait(long invId, ClaimItem item);       // 重载，靠入参类型区分
    int pending(); boolean awaitIdle(long timeoutMs); MergeMetrics metrics();
    @PreDestroy void close();
}
public interface IQuotaMergeFlushHandler {                        // 一组一个事务，两个失败域不合成一个
    List<DeductResult> flushDeductGroup(InventorySegmentVO segment, long invId, List<InventoryDeductRequestVO> requests);
    List<ClaimOutcome>  flushClaimGroup(long invId, List<ClaimItem> items);
}
```

**枚举名落地**（票 05 §5 的三出口 + 引擎三态，06 / 07 的 `switch` 按这个写）：

| 预扣 `DeductResult` | 确认 `ClaimOutcome` | 调用方动作 |
|---|---|---|
| `DEDUCTED` | `CONFIRMED` | 继续（已提交） |
| `INSUFFICIENT` | —（确认路径不存在） | 切兜底档 |
| `TIMEOUT_UNKNOWN` | `TIMEOUT_UNKNOWN` | 预扣：不切兜底、抛可重试；确认：与 `FAILED` 合一抛可重试 |
| `BUFFER_FULL` / `CLOSED` | 同左 | 调用方线程同步调同一个落库方法（组大小 1） |
| `FAILED` | `FAILED` | 抛可重试（确认的 `waiting` 不足也落这里） |

三处刻意的取舍：

1. **分组键是「动作 × segment × invId」**，比 01 多一维 `segment`——两段是两张表、两个 DAO（spec §4.1 原文就是「动作类型 × segment」）；`segment` 从 `InventoryDeductRequestVO.segment()` 读，合并器仍不解析 payload 推导 `shardKey`。
2. **`submitAndWait(invId, request)` 校验 `request.invId() == invId`**：同一行给两个 key 会让两个分片各自合并同一行，单飞与「确认看到已落账的 waiting」同时失效。这一条比 01 多，因为库存侧的请求里本来就带着 `invId`（不校验等于把重复字段的坑留给调用方）。
3. **A2 的并发线程数取 16（> `maxBatch` 10）**：库存侧每个调用方线程同时只挂一件（都在等结果），线程数不够就攒不满一批，`bringForward` 撞不上「正在落库」，单飞写坏也测不出来。已用变异验证：去掉 `running` 判据后该用例稳定红（`maxInFlight == 2`）。

## 代码评审留下的三条（一条已修，两条交给下游）

1. **已修**：`MergeConfigTest` 后两条用例名改成与 01 逐字（`rejectsOnlyNonPositiveFlushThreads` /
   `foldsHighBitsSoSnowflakeIdsSpreadAcrossShards`），`DisplayName` 也照抄 01 的措辞；
   `MergeMetrics.flushed` 的口径写清「库存侧含 `INSUFFICIENT`——业务结果不是丢失」（并补了一条用例锁住它，
   以及一条 `TIMEOUT_UNKNOWN` 的用例：超时的件之后确实落库了，这正是第三态存在的理由）。
2. **票面口径修正**：`Pending` 的<b>方法</b>与 field 语义（`future` / `settled` / `complete` 幂等 / `await`）
   与 01 一致，但字段比 01 多——`action` / `segment` / `payload` / `failureValue`（01 是 `event` + 可空 future）。
   这是「两个结果枚举 + 两段表」的结构性后果，不是漂移；照字面「`Pending` 逐字相同」在这边做不到。
3. **交给 06**：`QuotaMergeBuffer` 是基础设施层的类，`InventoryQuotaServiceImpl`（domain）不能直接注入它
   （`backend/CLAUDE.md` §1「domain 的 adapter 层只定义接口，由 infrastructure 实现」）。接线时先在
   `domain/inventory/adapter/port/` 加一个端口（如 `IQuotaMergePort`，方法就是这两个 `submitAndWait`），
   由 `QuotaMergeBuffer implements` 它，再把 `@Component` 补在这个实现类上、把 `IQuotaMergeFlushHandler`
   的真实实现落在 `infrastructure/inventory/`（两个 `@Transactional` 组方法）。本票不预先造这个端口，
   免得和 06 的落库形状打架。


