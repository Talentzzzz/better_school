# 06 · 两域重建 job 与收口

Type: task
Status: resolved
Blocked by: 02, 03, 04, 05

## Task

照帖子票 07 的先例收口：

1. **重建 job**：两域索引表各一个 xxl-job 手动触发方法（或一个 job 带域参数，照 `feedIndexRebuildJob` 样板，默认不调度）——清空重灌索引表（行集/feed_time/价格/view_count 与票 01 回填同口径，view_count 从计数加速表抄入）+ 计数加速表校准成明细行数（ODKU 补缺行/刷漂移 + 清孤儿）；一个事务，读者不见空窗。
2. **存量收口**：`product.view_count` 列的处置（若票 01 走独立计数表则本票出删列脚本 + `sql/backfill/` 处置存量库；若复用该列则只留口径注释，票内按票 01 结论执行）；票 01 的 backfill 脚本随收口退役的段落处理照帖子先例（头注释保留原文）。
3. **docs 同步**：docs/db、docs/api、sql.md 口径段同步；CONTEXT.md 复查（浏览量词条已在票 02 改写，本票查漏：热度排序、浏览记录分工若有词条缺口在此补）。
4. 集成测试：严格一致扫描（索引表与源表逐行比对 state/feed_time/价格/计数）+ 漂移修复（窄表直改、孤儿行、缺行）+ 幂等不动点，照 `FeedIndexRebuildIntegrationTest`。
5. 全量 bs-app 套件绿，map 收口。

## Answer

### 重建 job（两域各一个 handler）

- `bs-trigger/.../job/MarketFeedIndexRebuildJob.java`：`@XxlJob("productFeedIndexRebuildJob")` / `@XxlJob("errandFeedIndexRebuildJob")` → `IProductService.rebuildFeedIndex` / `IErrandOrderService.rebuildFeedIndex` → `TradeRepository` / `ErrandRepository` 的 `rebuildFeedIndex`。**没有做成「一个 job 带域参数」**：两域的数据源表、索引价格列（售价 / 悬赏）与驻留口径（商品行按可见态流转、跑腿行常驻）都不同，参数化只会在 handler 入口长出一段分支；域内各写一份与票 04「游标各域各写」同一取舍。仓储内**四条语句一个事务**：计数窄表重投影（ODKU 补缺行 / 刷漂移）→ 清孤儿计数行 → 清空索引 → 重灌投影；清空用 `DELETE` 不用 `TRUNCATE`（隐式提交会毁掉原子性），读者要么看到重建前、要么看到重建后的完整索引，不见空窗。
- SQL 落两域 mapper（与票 03/04 的索引读写语句同 DAO 同 mapper），DAO 各四条：`clearFeedIndex` / `rebuildFeedIndexProjection` / `deleteViewCountsMissingHistory` / `recalibrateViewCounts`。
- **重灌口径**（与票 01 回填同源）：行集 = 商品 `ON_SALE` ∪ 有过 `APPROVE` 审批（跑腿 = `PUBLISHED` ∪ 有过 `APPROVE`——行常驻、通过前流转点不产行，与运行期「行诞生于审核通过」一致）；`feed_time = COALESCE(MAX(APPROVE), create_time)`；`price = product.price / errand.reward`；`view_count` 从计数窄表抄入（无行 = 0）。
- **view_count 抄窄表而非抄索引旧值**：窄表是 UV 真值，这一步顺带把「过审前被浏览、浏览旁路命中 0 行」那段漏计追平（票 03 把这情形显式交给重建 job），也让热度排序键与详情读口径重新对齐。
- **调度**：`xxl-job-tasks.sql` 注册两条，`trigger_status = 0`（手动执行，不自动调度）。漂移信号 = 票 03 留下的口子「浏览旁路 `updateFeedIndexViewCount` 命中 0 行的 warn」与「计数窄表被绕过直改」，出现时到 xxl-job-admin 手动执行。

### 存量收口

- **`product.view_count` 删列**（票 01 拍板「计数走独立窄表、该列走退役」，本票执行到终点）：`schema.sql` 删列 + 注记；`ProductMapper.xml` 的 `insert` 去列（`baseColumns` 仍从 `product_view_count` 投影 `viewCount`，`ProductPO.viewCount` 保留为查询投影列，同帖子 `PostPO` 口径）；新增 `sql/backfill/market-feed-uv-drop-columns.sql`（不幂等、头注释含执行序与「值不再变化」的停写确认手段）。
- **票 01 的清零脚本整脚本退役**（`market-feed-uv-view-count-reset.sql` 删除）：它的全部作用是把这张**将被删的列**与索引热度键归零，列不存在后脚本不可执行；原文保留在删列脚本头注释里，仅供仍带旧列存量的更早线上库在切码前照做（切码后索引热度键已是 UV 真值，任何脚本都不许再归零）——处理方式照帖子票 07 把退役的搬数段留进头注释的先例。
- **票 01 回填脚本的 PV 搬数段退役**：`market-feed-uv-backfill.sql` ① 段的 `view_count` 抄入移除（列已不存在），原文同样留在头注释；② 跑腿段的 ODKU 也不再写 `view_count`（重投影不该把 UV 抹成 0）。脚本从此**只服务切码前的存量库**：它投影行集 / `feed_time` / 价格 / 不可见态，不校准计数、不碰热度键——切码后修索引一律走重建 job。
- 票 01 的 Answer 保留原文（历史决策记录），退役结论记在本票与 map「Decisions so far」。

### docs 同步

- `sql.md`：`product` DDL 去列 + 删列注记；两域 `feed_index` 说明段补「漂移由各域重建 job 手动兜底」；两域计数表说明段改「清零脚本随删列退役、删列脚本见 drop-columns、加速列漂移由重建 job 校准」。
- `docs/db/trade.md`：`product` 字段表去 `view_count`，加「已删除的列」注记（照 `docs/db/community.md` 的 `post` 先例）。
- `docs/api/trade.md` + `errand.md`：推荐列表读路径段补「索引行与计数窄表漂移由对应重建 job 手动兜底（清空重灌 + 计数校准，一个事务）」。
- `CONTEXT.md` 复查：**热度**（票 04）与**浏览记录分工**（票 05，写在「浏览量」词条里）两条词条已齐、无缺口；只把「浏览量」词条里的「`product.view_count` 旧 PV 列退役」改准为「已删列」。

### 测试

- 新增 `ProductFeedIndexRebuildIntegrationTest`（trade，2 用例）与 `ErrandFeedIndexRebuildIntegrationTest`（errand，2 用例），经 trigger handler 调真实链路：
  - ① 严格一致 + 漂移修复 + 幂等：真实链路四态（通过 / 通过后软删（跑腿为取消）/ 从未通过审 / 存量直插可见态）+ 人工制造缺行（先删索引行再浏览，旁路 0 行）与跨表直改（窄表刷 99）+ 过审前被浏览的漏计 + 孤儿计数行，重建后逐行扫断言 `state / price / view_count / feed_time` 与源表一致（`feed_time = MAX(APPROVE)` 兜底 `create_time`）、该有行的都有行、计数窄表 ≡ 明细行数；重跑是幂等不动点。
  - ② 计数校准：窄表直改漂移、计数行误删、孤儿行三种损伤，重建后窄表 ≡ 明细行数、索引热度键同步追平。
- 存量测试随删列清理（照帖子先例）：`MarketFeedUvBackfillIntegrationTest`（去掉清零脚本用例与 PV 抄入断言，回填用例改断言「热度键不抄存量 PV、从 0 起」）、`ProductViewMergeIntegrationTest`（去掉主表旧 PV 列停写断言）；两域直插商品夹具去掉 `view_count` 列（`ProductFeedIndexSync` / `ProductFeedCursorPaging` / `ProductViewed` / `TradeDraftBox` / `TradePublishPendingReview` / `AuthorProfile` / `JobFanoutVirtualThread` / `SearchIndexRebuildJob` / `UnboundedQueryCursor`）。
- 新测 4/4 绿；全量 bs-app **393 tests, 0 failures**（BUILD SUCCESS，390 存量 − 1（清零脚本用例随脚本退役）+ 本票净增 4，见 `mvn-ticket06-full.log`）。

### 已知口径注记

- **两域重建 SQL / 仓储方法逐字同形是刻意的**：map 定「两域对称、各域各写、不跨域泛化」，与 `CommunityRepository.rebuildFeedIndex` 也是同一形状；抽公共抽象要把两域的表名与索引列塞进参数，反而回到泛化那条被否掉的路。
- **测试夹具的 `approve` 会补一条与索引 `feed_time` 对齐的 APPROVE 流水**：真实链路里「审核记录」与「索引 `feed_time`」出自同一事件（`DATETIME` 秒级精度下可能跨秒），夹具按索引落下的时刻补记录，让 `MAX(APPROVE)` 这条投影口径在数据上严格成立——不是迁就实现，而是把测试库摆成线上该有的样子（帖子先例的同类断言存在同一边界）。
- **重建会改写「过审前浏览」的既有分配**：这些 UV 在运行期旁路里被记进窄表却没进索引（0 行 warn），重建把索引热度键对齐窄表真值——这是修复而非口径变化。
