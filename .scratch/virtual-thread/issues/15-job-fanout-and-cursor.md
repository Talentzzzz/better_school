# 15: 调度扇出——8 处扇出 + 无界查询游标化

**What to build:** spec §4.3：单体 7 个 job 入口（`tradeExpireScan` / `wantToBuyTimeoutScan` / `productTimeoutScan` / `errandTimeoutScan` / `postReviewTimeoutScan` / `dailyCreditJob` / `searchIndexRebuildJob`）+ 单体 `onTimeoutBatch` 批处理共 8 处，把串行循环按**扇出模板两变体**改并行（模板全文与四条硬规矩在 spec §4.3 B，不在此重复）。要点：变体 B（`dailyCreditJob` / `searchIndexRebuildJob`）**必须先补 `LIMIT` + 游标再扇出**；并发宽度 = 批大小、不加信号量；执行器即用即关（粒度 = 一次 tick / 一次批调用）；factory 命名规范；结果回主线程汇总；项内异常吞并记账；⚠️ `close()` 无限等待 ⇒ 项内自带超时（Dubbo 2s / DB `connection-timeout` / xxl-job 调度超时）。`onTimeoutBatch` 只动批内并行，中心侧 `scan.batch-size` 与 2s 超时不动；`successList`/`failList` 回主线程汇总。

**无界查询处置**（spec §4.3 C）：`selectUsersActiveOn`（不自然推进）与 4 处 `selectAllIds`（静默不完整索引）补 `LIMIT` + 游标；`selectLowScoreNormalUsers` 能自推进、为统一仍走游标。**唯一例外**：`PostDetailCache.initBloom()` 全量 id 刻意不设限，**不许「修」**。

语义侧安全依据已逐条核过（spec §4.3 A：幂等闸门全保留、资金/信用更新全是原子 SQL），本票不做语义变更；spec §4.3 F 的同用户并发关单死锁风险是**已知暴露面，本票不修**，只保证关单路径日志可观测（出现 `Deadlock found` 即触发 spec §4.3 F 修法，另立票）。

带调度扇出 seam 断言（spec §5.2 #3）：job 触发测试中断言**扇出项内** `isVirtual()`——断言对象是扇出项**不是 handler 本身**（`JobThread` 是 xxl 自有池平台线程）。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] 8 处扇出全部按模板改造（factory 命名、即用即关、宽度 = 批大小、无信号量、项内自带超时）
- [x] 变体 B 两处先落 `LIMIT` + 游标，游标推进到空批是唯一终止条件
- [x] 5 处无界查询游标化完成，`initBloom` 保持全量不动
- [x] 幂等 / 资金 / 信用相关既有测试全绿（语义不变）
- [x] 调度扇出 seam 断言绿（扇出项内为 VT）

## Answer

**落地口径**：spec §4.3 的 **8 处扇出**全部改并行、**5 处无界查询**（4 × `selectAllIds` + `selectUsersActiveOn`）＋低分封禁兜底查询全部游标化；中心侧 `scan.batch-size` 与 2s 超时未动；**语义零变更**（幂等闸门 / 原子 SQL / 顺序假设一律未碰）；`PostDetailCache.initBloom()` 的全量 id 按 spec §4.3 C 的例外**刻意不动**。

**8 处扇出**（四条硬规矩 + `close()` 无限等待 caveat 全部落在 helper 上，见下）：

| 入口 | 扇出项 | 线程名前缀 |
| --- | --- | --- |
| `tradeExpireScan` | 到期商品 / 到期求购 / 待审提醒（商品、求购各一批） | `trade-expire-product-`、`trade-expire-want-`、`trade-remind-product-`、`trade-remind-want-` |
| `wantToBuyTimeoutScan` | 待支付求购 | `want-pay-timeout-` |
| `productTimeoutScan` | 待支付商品单 | `product-pay-timeout-` |
| `errandTimeoutScan` | 待支付单 / 超时单 / 待审提醒 | `errand-pay-timeout-`、`errand-timeout-`、`errand-remind-` |
| `postReviewTimeoutScan` | 待审帖提醒 | `post-remind-` |
| `dailyCreditJob` | 昨日活跃用户（加分）/ 低分用户（封禁） | `credit-active-`、`credit-ban-` |
| `searchIndexRebuildJob` | 四类索引重建 | `search-rebuild-{post,product,errand,user}-` |
| `onTimeoutBatch`（Dubbo provider） | 中心批内逐项关单 | `timeout-callback-` |

提醒类扇出项 = **一条待审**，其下逐收件人仍是串行循环（项内自行吞异常，语义没动）；`onTimeoutBatch` 的成功/失败清单仍按入参顺序回主线程汇总（`closeOne` 返回自己的 key、失败返回 null）。

**变体 B（游标）**：`dailyCreditJob` / `searchIndexRebuildJob` 先补 `LIMIT` + 游标再扇出，**游标推进到空批是唯一终止条件**；`searchIndexRebuildJob` 顺带删掉 `chunks()`——旧实现「取全部 id 再切块」在没有 LIMIT 时只重建前 N 行，是**静默不完整的索引**。批大小：搜索重建沿用既有 `REBUILD_BATCH_SIZE = 500`（现含义 = 游标批），信用任务按各域扫描惯例取 `SCAN_BATCH_SIZE = 100`。

**无界查询**：新增 `selectIdsAfter(lastId, limit)`（post/product/errand/user 四表 `id > ? ORDER BY id LIMIT ?`）、`selectUsersActiveOnAfter(activeDate, lastUserId, limit)`、`selectLowScoreNormalUsersAfter(lastUserId, limit)`；失去调用者的 `selectAllIds`（product/errand 的 DAO + mapper）一并删除；post/user 的 `selectAllIds` 保留给布隆预热与公告广播（都不是循环）。仓储侧对应改为 `listIdsAfter` / `listUserIdsAfter` / `listUsersActiveOnAfter` / `listLowScoreNormalUsersAfter`。

**seam 断言（spec §5.2 #3）**：`VirtualThreadFanout` 由 `bs-app/config/FanoutConfig` 装配成 Bean，测试侧用 test classpath 的 `@Primary` 探针 `VtJobFanoutProbe` 继承它，在**扇出项执行的那条线程上**记 `isVirtual()` 与线程名再原样委派——断言对象是扇出项，不是 handler（`JobThread` 是 xxl 自有池平台线程，不在断言范围）。变体 A / B 各一条用例，各断言三件：项计数 > 0（被改回串行 ⇒ 恒 0）、平台线程项计数 = 0、线程名符合模板命名规范（spec §5.1 的命名规则顺带被钉住）。

**测试**：单体 `mvn -o test` **364 用例全绿**（0 失败 0 错误），含本票新增 8 条（2 seam + 5 游标 + 1 重建）。另两条：

- **游标页**（`UnboundedQueryCursorIntegrationTest`）：六条游标查询逐个「首页 → 下一页 → 空批」，断言页内升序、本页首行严格大于上页末行（处理过的行不重复出现）、空批终止；特别覆盖两处「不会自然收敛」的查询（`user_daily_active` 处理完仍在结果集里、低分用户封禁前不退出结果集）。游标若不推进会在同一页打转，用行数上限兜住死循环。
- **搜索重建**（`SearchIndexRebuildJobIntegrationTest`）：测试栈刻意把 ES 指向不可达端口（`application-test.yml`），故用 test 侧 `@Primary` 替身 `FakeSearchRepository` 记录「清了哪些索引、bulk 写了哪些文档」，让重建的游标 + 扇出 + 状态过滤 + 汇总在真 MySQL 上跑一遍：四类索引各清一次且顺序固定、只有公开状态的行进索引（待审帖 / 已售商品 / 已取消跑单都不进）、全部 NORMAL 用户进索引。写这条用例时顺带证实：重建项内的坏行（如 category 为 NULL 的商品）会被规矩 4 吞掉并记 ERROR——见下「已知波及」。

**code-review 采纳项**（两轴都出真货）：

- **Spec 轴**：① `CreditDailyCase` 的两个通知循环**撤回串行**——它们遍历的是一整天累积的结果（跨全部游标批），扇出去宽度 = 当日全量用户，违反 §4.3 B 规矩 1「并发宽度 = 批大小」；本 job 的扇出仍在域层两个游标批里（宽度 100）。② `VirtualThreadFanout.awaitItem` 原先把 `ExecutionException` 一律吞成「本项跳过」：项内 `Exception` 已被 `invokeItem` 兜住，能走到那一步的只剩 `Error`（OOM 之类），而规矩 4 只管「项内异常」⇒ 改成 `Error` 原样抛出，别把进程级故障悄悄降级成一项失败。
- **Standards 轴**：① `SearchServiceImpl` 四个重建循环逐字同构（旧的 `chunks()` 曾承担这个共享形状）⇒ 抽成 `rebuild(index, threadPrefix, pageOfIds, docOf, bulkUpsert)`；② `CreditAccountServiceImpl` 两个游标循环同理抽成 `collectChangedUsers(threadPrefix, pageOfUserIds, changeOfUser)`。

**未采纳（记账，不是遗漏）**：

- **模板落地为共享组件而非每处内联**：spec §4.3 B 把模板写成「每处一段 try-with-resources」，本票把它收进 `com.betterschool.types.concurrent.VirtualThreadFanout`（`forEach` / `map` 两个入口）。理由：规矩 1–4 与 `close()` caveat 是**每处都必须一样**的东西，内联 14 次就是 14 份可以各自漂移的副本；组件化后 seam 断言才有可能**一个探针覆盖全部 8 处**（spec §5.3 的「扇出被改回串行 ⇒ 断言红」靠它成立）。spec §5.2 已把断言 helper 的实现下放给落地票。
- **helper 放在 `bs-types`**：`backend/CLAUDE.md` §7 把 types 写成「常量 / 枚举 / 异常」，此处多了一个有行为的并发原语；备选是 `bs-domain` 的 `domain/common` 包（但 §2 把 domain 划为业务域）与 `bs-app/config`（case/domain 看不到）。8 处扇出全在单体，而 `bs-types` 是 domain / case / trigger 都能看到的唯一模块 ⇒ 放这里、Bean 装配放 `bs-app/config`（§6），并为它加 `org.slf4j:slf4j-api`（照 bs-domain / bs-case 已有写法——项内异常必须记账）。两轴都把它标成 judgement call 而非硬违规。
- **搜索重建改为每行一次单行查询**：旧实现每 500 行一次 IN 查询，现在每个扇出项 `listByIds(List.of(id))` 一次（DB 往返变多，换到批内并行与模板一致的单 id 项）。spec §4.3 B 变体 B 的模板本身就是 `ids.stream().map(id -> …one(id))` 单 id 项，故按模板办；若后续嫌往返多，可改为「批内按块读 + 块内扇出」。
- **测试夹具的私有 helper 重复**（`publishPendingPost`、裸 `INSERT INTO product/errand_order` 在两个新用例里各一份）：与既有 `PostReviewTimeoutRemindIntegrationTest` 的做法一致（同类用例各自私有 helper），未去动公共基类。

**已知波及 / 范围外**：

- **项内异常不再上冒**（规矩 4 的直接后果，值得点名）：某个 job 的单项失败**不会**让这次 tick 失败、也不会被 xxl-job 记成失败，只有一行带前缀的 ERROR 日志。对「当天错过就要等明天」的动作（每日信用任务加分明细）意味着一次坏数据会让该用户本轮被跳过——spec §4.3 A 已按「批量信封为部分失败设计」核过语义，此处留档以免日后误判为漏处理。
- **同用户并发关单死锁**（spec §4.3 F）：本版不修，关单路径日志可观测（扇出失败按 ERROR 记、异常栈里带 `Deadlock found`）；出现即触发 spec 的「删 `wallet_id` 列」修法（另立票）。
- **搜索重建没有真 ES 验收**：测试栈刻意不连 ES，替身只验重建算法；真 ES 侧的调用形状未变（每批一次 `bulkUpsert*`，空批由实现自身兜住），`clearIndex` 连带 `ensureIndices()` 的行为也未动。
- **常态化 JFR / 平台线程数基线对照 / 终验归票 16**（票 16 的前置 12–15 现已全部清）。
