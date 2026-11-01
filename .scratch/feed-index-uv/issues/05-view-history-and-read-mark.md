# 05 · 浏览历史接口与 feed 已读灰显

Type: task
Status: resolved
Blocked by: 02

## Task

消费 `user_view_history` 的两个读能力：

1. 「我的浏览历史」接口：`WHERE user_id=? ORDER BY viewed_at DESC` 走二级索引，出 post_id 后 `selectByIds` 回查组装；已删/被拒帖的展示形态在此票与前端对齐（失效卡片或过滤，map 的 fog 项在此收敛）
2. feed 已读灰显：`listPosts` 组装时批量 `IN` 查当前用户在页内 post_id 的浏览记录（走 PK），响应带 viewed 标记
3. 集成测试：历史倒序与分页、未登录/无历史空态、已读标记与浏览事件的一致性

## Answer

### 交付物

- **雾项收敛：已删/被拒帖在历史里选择过滤，不做失效卡片**。可见性口径 = 详情可见性：`p.state='NORMAL' OR (p.user_id=本人 AND p.state!='DELETED')`——本人浏览过的待审/驳回/草稿帖照常展示（详情对作者可见），已删一律过滤。理由：历史页点卡片要能进详情，非可见态进详情是 404；失效卡片需要占位 UI 与额外字段，无产品诉求。map「Not yet specified」第一条就此关闭。
- **历史分页一条 JOIN SQL**（`selectViewHistoryPage`/`countViewHistory`，与 `selectLikedPageByUser` 同型）：`idx_user_viewed (user_id, viewed_at)` 倒序扫描 + `viewed_at DESC, post_id DESC` tie-break（viewed_at 秒精度并列收敛确定序）。**票面「出 post_id 后 selectByIds 回查组装」的一处有意偏离**：可见性谓词必须在 SQL 里，offset 翻页窗口与 COUNT 才同口径（Java 侧过滤会造成跨页重/漏），JOIN 还顺带省一次回查；未照搬 `selectByIds` 是因为这个原因，不是遗漏。
- **接口**：`GET /api/community/posts/history`（page/pageSize 常规分页，total 精确）。GET `/api/community/**` 在 Security 白名单内匿名可达——浏览历史是个人数据，控制器显式判 `currentUserId == null` 返回空页（与前端 `/me/history` requiresAuth 双保险）。
- **feed 已读灰显**：`listPosts` 组装后批量回查页内 post_id 的浏览明细（`selectViewedPostIds`，走 `(user_id, post_id)` 主键前缀，匿名/空页直接空集），`PostSummaryResponseDTO` 加 `viewed` 字段——仅主页 feed 接口填充，未登录 false，其余列表恒 null。`PostCard` 根节点 `viewed` class 整卡降不透明度（0.55）。
- **前端**：新 `ViewHistoryView`（`/me/history`，useWaterfall 分页 + PostCard 瀑布流 + 空态「去逛逛」）；入口 = ProfileView hero 卡片区第三张「浏览历史」卡。
- **文档**：CONTEXT.md「浏览量」词条补一句「浏览明细同时是浏览历史与 feed 已读灰显的数据源」；docs/api/community.md 加 history 接口与 viewed 字段说明。

### 决策点（票面没写死的）

- **失效帖过滤而非失效卡片**（雾项收敛，见上）。
- **历史走 JOIN 而非「出 id 后回查」**：offset 分页完整性要求谓词在 SQL 内（见上）。
- **匿名 GET history 返回 200 空页而非 401**：白名单是全局的（`GET /api/community/**` permitAll），不为单接口开洞；个人数据不外露由空页保证，语义上「匿名没有历史」也成立。

### 验证

- 新增 `ViewHistoryIntegrationTest`（4 用例）：① 25 帖逐帖浏览 → 历史三页翻完不重不漏、整序 = 库内明细序、total 精确 25；② 匿名与无历史用户均空页（total 0）；③ 已删的他人帖过滤、本人待审帖照常展示、total 与过滤口径一致；④ feed viewed 标记与浏览事件一致（浏览过的 true、没浏览的 false、匿名全 false）。
- 新测试类 4/4 绿；前端 `vue-tsc --noEmit` 过。

