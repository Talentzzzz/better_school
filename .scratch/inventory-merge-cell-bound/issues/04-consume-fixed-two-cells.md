# 票 04 · 消费侧固定合两格并删除续命

Type: task
Status: resolved

Blocked by: 02, 03

## Question

把合并的消费入口从「查 `MIN` 循环 ≤10 格 + 续命」改成「按消息给的 `cellTime` 合 `s` 与 `s+1` 两格，结束」。这是整次换轨的核心，也是删掉续命的动作。

范围：

1. `IInventoryMergeService`：新增 `MergeKeyResultVO mergePair(InventorySegmentVO segment, long invId, int bucketId, LocalDateTime cellTime)`。
   **不要**用「`cellTime == null` 就退化成查 `MIN`」一个方法糊两条路径——消息路径和兜底路径的语义不同，必须是两个方法。
2. `InventoryMergeServiceImpl.mergePair`：
   ```java
   int cells = 0; long details = 0;
   for (LocalDateTime t : List.of(cellTime, cellTime.plusSeconds(1))) {
       int merged = repository.mergeCell(segment, invId, bucketId, t);
       // merged == 0 不 break：第二格的 0 是正常覆盖，第一格的 0 也不影响第二格
       if (merged > 0) { cells++; details += merged; }
   }
   return new MergeKeyResultVO(cells, details);
   ```
   顺序必须是 `s` → `s+1`（同线程串行），这样 `waiting_qty >= s2` 才是结构保证。
3. **删掉续命**：`mergeKey` 里的 `existsUnmergedDetail` + `enqueueMergeMessage` 调用整段删除；`mergeKey` 保留给兜底（仍然查 `MIN` 循环 ≤10 格，但不再发消息）。
4. `InventoryMergeConsumer`：校验 `cellTime != null`（缺了就丢弃并 `log.error`，与现有「坏载荷丢弃」一致），改调 `mergePair`。
5. 日志：
   - `mergeCell` 里「竞态，整格回滚」那条 `info` → `debug`，措辞改成「本格已由前一条消息合并」——固定两格下第二格必然空转，它不是异常。
   - 新增一条 `debug` 记录两次 `mergeCell` 的返回，便于观测空转率。
6. 顺手删掉不再被引用的方法（`existsUnmergedDetail` 若只有续命在用）。

**这一票只改消费侧，不碰预扣与 outbox。**

## Answer

### 1. 改了什么

| 位置 | 改动 |
| --- | --- |
| `IInventoryMergeService` | 新增 `mergePair(segment, invId, bucketId, cellTime)`；`mergeKey` 的 javadoc 改成「兜底专用、只合不发」；接口头写清两条入口职责不同、**不用空值判断糊成一条** |
| `InventoryMergeServiceImpl.mergePair` | 固定两次 `mergeCell`：先 `cellTime`、再 `cellTime.plusSeconds(1)`，同线程串行；某一格返回 0 **不中断另一格**；`cells` / `details` 由两次返回值累加；一条 `debug` 记 `merged=[x, y]` 供观测空转率 |
| `InventoryMergeServiceImpl.mergeKey` | 删掉 `existsUnmergedDetail` + `enqueueMergeMessage` 整段（连 `failed` 标志一起没了——它只为「失败不续命」存在）；保留 `MIN` 循环 ≤ 10 格，改用于兜底 |
| `InventoryMergeConsumer` | 校验顺序 = 解析 → `null` → `invId/bucketId` → `segment` → **`cellTime`**；缺 `cellTime` 走 `log.error` + 丢弃，与既有坏载荷同待遇；改调 `mergePair` |
| `InventoryRepository.mergeCell` | 「已无可合行」与「翻转行数对不上」两条 `info` → `debug`，措辞按实际成因写（「本格已由前一条消息合并」/「多为已被前一条消息合并」） |
| `IInventoryRepository` / `IPhysicalQuotaDao` / `IVirtualInventoryQuotaDao` / 两个 mapper XML | 删除 `existsUnmergedDetail`（接口 + 实现 + DAO + SQL，共 4 处）与 `enqueueMergeMessage`；`queryOldestUnmergedCellTime` 标注「只有兜底用」 |
| `Constants` | 删除 `INVENTORY_MERGE_DELAY_LEVEL`（唯一调用方是续命）；`MERGE_MAX_CELLS_PER_KEY` 的注释从「一次唤醒」改成「兜底一次」 |
| `InventoryMergeMessage` / `MergeKeyResultVO` | javadoc 跟上：三参构造「不能直接发」、消费侧认 `cellTime`；结果 VO 的 `cells` 上限从「≤10」变成「消息路径 ≤2 / 兜底 ≤10」 |
| `InventoryMergeEngineIntegrationTest` | **最小修补**保编译与绿：删掉断言续命的用例、`mergeKey_capsAtTenCells` 的续命断言改 0、消费者用例的合法载荷补 `cellTime` 并加一条「缺 `cellTime` 丢弃」。完整重写仍是票 05 |

### 2. 四个判断（票面没写死的地方）

1. **两条入口不合并成一个 `mergeCell(..., cellTime)`**。消息路径知道该合哪两格，兜底路径不知道——把两种语义塞进「`cellTime == null` 就查 `MIN`」会让「这条消息是不是坏的」变成不可判定（`null` 既可能是兜底调用，也可能是坏载荷）。所以 `mergePair` / `mergeKey` 并存，消费侧只认 `cellTime` 非空。
2. **「已无可合行」那条 `info` 也降到了 `debug`**（票面只点名「翻转行数对不上」那条）。固定两格方案下，第二格空转根本走不到「翻转行数对不上」——它在更早的 `totalCount == 0` 分支就返回了，真正每秒命中的是这一条。只降票面点名的那条等于没降噪，所以两条一起降，且措辞按各自成因分开写。
3. **`INVENTORY_MERGE_DELAY_LEVEL` 一并删掉**（票面第 6 条只说「方法」）：它唯一的调用方是 `enqueueMergeMessage`，留着就是一条「文档说只剩续命在用、而续命已经不存在」的死常量。全仓（含单体 `better-school/`）已无引用。
4. **滚动发布期间旧载荷（无 `cellTime`）直接丢弃、不退化查 `MIN`**——这就是 map「Not yet specified」里那条的答案。理由同 2.1：退化会让坏载荷变成一条合法的 `mergeKey`，把「消息丢了」和「消息坏了」混成一种观测。代价是切换窗口内的旧消息落到 60s 兜底，可接受（兜底本来就是「消息整条丢了」的收口）。

### 3. 边界

- **第二格的 0 是结构性的、不是异常**：一对格子 `(s, s+1)` 会被 `cellTime = s` 与 `cellTime = s+1` 两条消息各指一次（前后相邻两批明细），其中一条必然空转。所以 `mergeCell` 在这里返回 0 既不是竞态也不是失败，日志只能给 `debug`（`error` 会误报不变量破坏，`info` 会按秒刷屏）。
- **顺序保证只看 `s → s+1`**：`waiting_qty >= s2` 依赖「`type = 1` 那一格先合」，而 `type = 1` 的 `create_time` 必然早于同一批的 `type = 2`；同线程串行两次调用就够，不需要锁、不需要 `FOR UPDATE`。
- **兜底语义没变**：仍是 `MIN` 循环 ≤ 10 格、失败即停、不抛；只是不再发消息。`backstopOnce` 一行没动。

### 4. 测试

`mvn -o test`（marketing 全量）：**133 tests 绿**。比票 03 的 134 少 1 条——删掉的是断言续命的那条用例，它断言的语义已经不存在；「不续命」的新断言（消费完不产生 outbox 行）由票 05 正式补。

> 环境备注：本轮开跑时全量集成用例在 `TestingEnvironment.publishSharedConfig` 集体失败，原因是 `bs-nacos-test` 容器的 `18848` 端口没有映射（`docker ps` 里该容器无 Ports，`netstat` 也没有监听）。按 compose 重建（`docker compose up -d --no-deps nacos-test`）后恢复正常，与本次改动无关。

**遗留（不属于这一票）**：`backend/marketing/docs/db/inventory.md`、`docs/api/lottery.md`、`CONTEXT.md`、ADR-0011 仍写着「续命 / 延迟级别 1 / 只合最老一格」，由票 06 统一改；集成测试的完整重写在票 05。
