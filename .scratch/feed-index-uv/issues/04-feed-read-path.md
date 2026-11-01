# 04 · feed 读路径切换与游标分页

Type: task
Status: resolved
Blocked by: 03

## Task

主页 feed 从 DB 直查切到异构索引表：

1. `listPosts` 改走 `post_feed_index`：最新 `(state, feed_time)`、热榜 `(state, like_count)` 索引序扫描，出有序 post_id
2. 游标分页：`(排序键, post_id) < (?, ?)` 续页，接口入参出参带 cursor（首响应仍带 page/pageSize 兼容，或与前端约定一次切换）
3. `selectByIds` 批量回查组装（现成），liked 集合、摘要截断等后处理不变
4. 降级：索引表查询异常时 fallback 回旧 `selectPage` 直查（保留不删），日志告警
5. `countPosts` 的 total 降口径（下一页探测或缓存），不再强求精确 COUNT
6. 集成测试：两排序×游标翻页不重不漏、降级路径可用

## Answer

### 交付物

- **索引序扫描一条 SQL**（`selectFeedIndex`，与票 03 的写语句同 DAO 同 mapper）：`WHERE state='NORMAL'` + `(排序键, post_id) DESC` 与两个索引列序一致，续页谓词写同构比较（`like_count < ? OR (like_count = ? AND post_id < ?)`）走 range scan；部分列直映域 VO `FeedIndexItemVO`（排序键随行带出，编下一页游标用，与 `selectReviewTimeoutPosts` 同款）。
- **游标编解码收进域内**（`FeedCursorVO` record）：对外 Base64url 不透明串，内部 `sort|排序键|postId`；解码校验排序一致性——换 tab 复用旧游标 / 篡改内容一律 PARAM_ERROR，不静默错位。锚点工厂 `anchorOf(sortType, 扫描行)` 收拢 hot/time 分支。keyset 语义：游标失效（排序键漂移）不报错，最多边界附近行有出入，不重不漏由 post_id 唯一锚点保证。
- **读路径编排**（`PostServiceImpl.listPosts` 返回 `FeedPageVO{posts, nextCursor, indexPath}`）：无分类走索引——扫描多探一行探下页，`listByIds` 批量回查、按扫描序重排（索引行缺 post 行只跳过不补位，游标锚在扫描序上）；扫描为空 = 越过末尾返回**空页**（code-review 返工点：此前空扫描会落到 `listByIds([])` 的 `IN ()` SQL 错误被 catch 吞掉，静默降级成"重发第一页"）。带分类过滤（索引表无 category 列，票 06 退役前去参前暂存）走旧直查。
- **降级**：索引查询异常 `catch (Exception)` → warn 日志 → 旧 `selectPage` offset 直查（`indexPath=false`、不发游标，前端按 page 续页，连续性 best-effort——游标无法翻成 offset，这是降级路径的唯一一处 page 不被忽略）。降级直查的热榜口径已同步对齐索引表 = 纯 like_count（去掉 `+ comment_count*2` 权重公式，map 定稿），两路排序语义一致。
- **total 降口径**：索引路径不再 `countPosts`，total = -1（未知，注释钉住约定）；hasMore 用多探一行的下一页探测，`nextCursor` 只在确认有下页时发放。降级/分类路径 total 仍精确。
- **接口契约**：`/api/community/posts` 入参加 `cursor`（非空则忽略 page），`PageResult` 加可空 `cursor` 字段（其余 page 型接口恒 null，4 参老构造保留）。前端一次切换：`useWaterfall` 透传 cursor、`total < 0` 不参与 finished 判断（`WaterfallPage` 加可选 cursor，其余消费方 MarketView/MyOrdersView 不受影响）；HomeView 传 cursor。liked 集合、摘要截断等后处理原样未动。

### 已知口径注记

- **热榜语义随读路径切换即时生效**：主页热榜从 `like_count + comment_count*2` 变纯 like_count（map 定稿本就是本 effort 的事，读路径切换是唯一消费点）。降级路径同口径。
- **同秒 feed_time 并列**靠 post_id tie-break 收敛成确定序（DATETIME 秒精度）；测试期望序取自库内索引序，不假设发布时刻互异。
- **降级测试是白盒**（黑盒造不出索引表查询故障）：mock 仓储仅 `scanFeedIndex` 抛异常、其余委托真实实现，手搓 `PostServiceImpl` 走真库旧直查；黑盒侧另钉住分类路径（同为旧直查）的精确 total + 无游标。
- code-review spec 轴另发现「索引路径契约 vs 降级 page 续页」矛盾点已落上注记；standards 轴仅注记级（hot/time 分支散布——已顺手收拢 `anchorOf`、total=-1 哨兵——注释钉住）。

### 验证

- 新增 `FeedCursorPagingIntegrationTest`（7 用例）：① 最新排序游标翻页不重不漏（25 帖 3 页，整序 = 库内索引序，total=-1、page/pageSize 兼容回显）；② 热榜排序同上（赞数分档 0-3）；③ 游标锚点隔离跨页跳变（锚点行被顶到榜首后不再出现在后续页、两页无交集）；④ 分类过滤走旧直查（精确 total、无游标）；⑤ 非法/排序不符游标报 400；⑥ 越过末尾返回空页不触发降级（手工构造锚在末尾行上的游标——正常端点不会对末页发游标，越尾只来自陈旧客户端/行被删后的锚点）；⑦ 索引查询异常降级直查（白盒）。
- 新测试类 7/7 绿；全量 bs-app 套件 **353 tests, 0 failures**（BUILD SUCCESS）。
- 前端 `vue-tsc --noEmit` 过。
