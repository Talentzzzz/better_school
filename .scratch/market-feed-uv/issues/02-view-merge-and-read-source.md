# 02 · 浏览合并器接入与详情读路径换源

Type: task
Status: resolved
Blocked by: 01

## Task

照帖子 `viewPosts` 合并器的三步镜像，把闲置与跑腿的浏览落库换成 UV 口径：

1. **打点带人**：商品详情（`ProductServiceImpl.productDetail` → `recordProductView`）与跑腿详情补 userId 入参；ViewMergeWriter 的 PRODUCT_VIEW 改强制 userId > 0、新增 ERRAND_VIEW action（照 POST_VIEW 先例）。
2. **落库三步**（一组一个事务，照 `likePosts`/`viewPosts` 镜像）：INSERT IGNORE 明细（affected = 新增 UV 数）→ 全组刷 viewed_at → 计数加速列 += 新增数；两域各自 flushGroup（CountFlushDispatcher 加分发分支）。
3. **匿名不计入**（商品语义变更：现状匿名也计）：明细主键一半是 user_id，无主无处可落；详情接口匿名访问只跳过打点不报错。
4. **读路径换源**：商品列表/详情的 viewCount 从 `product.view_count` 列换到票 01 定稿的计数源；跑腿 `ErrandOrderResponseDTO` 补 viewCount 字段并接线。
5. **CONTEXT.md「浏览量」词条改写**：商品不再是「纯累加近似值」、跑腿纳入口径；匿名不计入的语义变更写明。
6. 集成测试：首次浏览计 1、重复浏览不计数只刷 viewed_at、匿名不计、两域计数与明细行数一致。

## Answer

### 实现口径（照帖子 viewPosts 三步逐点镜像）

- **打点带人**：`ITradeRepository.recordProductView(productId, userId)` 签名补 userId，`ProductServiceImpl.productDetail` 透传 `currentUserId`；跑腿 `IErrandRepository` 新增 `recordOrderView(orderId, userId)`，`ErrandOrderServiceImpl.orderDetail` 可见性判定通过后打点。两域仓储层 `userId == null` 直接 return（匿名不计入），`ViewMergeWriter` 收紧为**三域同规强制 userId > 0**（无主哨兵 0 会把全体匿名塌成明细表一行）——两参数无主重载删除。
- **落库三步**（一组一个事务，`CommunityRepository.viewPosts` 的镜像）：`INSERT IGNORE` 明细（影响行数 = 新增 UV 数）→ 全组刷 `viewed_at`（含刚插入行，重复浏览唯一可见效果）→ 计数表 ODKU `+= 新增数`。商品落 `TradeRepository.flushViewGroup`（原单条 `UPDATE product SET view_count + N` 退役，`updateCountDelta` SQL 随删）；跑腿落 `ErrandRepository.flushViewGroup`；`CountFlushDispatcher` 加 `ERRAND_VIEW` 分发分支（`CommunityRepository` 的兜底 throw 合并 `PRODUCT_VIEW, ERRAND_VIEW` 两 case）。
- **新动作**：`CountAction.ERRAND_VIEW`（商品/跑腿注释同步改 UV 口径）。
- **读路径换源**：`ProductMapper.xml` `baseColumns` 的 `p.view_count` 换 `IFNULL(pvc.view_count, 0)`（LEFT JOIN `product_view_count`，六个带 baseColumns 的查询全部换源——列表/详情/我的发布/他人主页在售/收藏口径一致）；主表列停读；落库三步也不再写它（insert 仍落默认 0，恒等不变式随删列一并退役），**删列随票 06**。跑腿 `ErrandOrderResponseDTO` 补 `viewCount`（long），`orderDetail` 里 `errandRepository.viewCountOf`（`errand_view_count` 加速列，行不存在按 0）填充实体后转换携带；**列表路径恒 0**（DTO 注释写明，跑腿此前无浏览量、非推荐列表不展示；推荐列表读索引快照属票 04）。
- **CONTEXT.md「浏览量」词条改写**：三域同构 UV 口径（明细表即事实源 + 加速列恒等明细行数）、匿名不计入是商品语义变更（旧 PV 口径作废、`product.view_count` 退役）、浏览明细数据源扩至市集页浏览痕迹；Avoid 词条同步。docs/api `trade.md` 详情行改写（顺带修正早已过期的「浏览计数经 outbox 异步 +1」）、`errand.md` 详情行补 viewCount。

### 测试

- `ViewMergeWriterTest`（纯内存 7 用例）：shardKey/停机降级用例补 VIEWER；`rejectsPostViewWithoutViewer` 改 `rejectsViewWithoutViewer`——三域（POST/PRODUCT/ERRAND）0 一律拦、零落库。
- `ProductViewMergeIntegrationTest` 重写（4 用例）：登录浏览计 1 + 详情响应读计数表 + **主表旧 PV 列停写**；重复浏览不计数只刷 viewed_at（预置一小时前明细，断言 viewed_at 变新）；匿名不计入（明细与加速列都不落）；一组三件（同人×2）合并 → 去重新增 2、加速列 = 明细行数，再一组全重复 → 幂等不动点。
- 新增 `ErrandViewMergeIntegrationTest`（3 用例）：登录浏览计 1 + **首次详情响应 viewCount=0（合并窗口未过，读库内当前值）排空后复请求=1**（接线语义钉死）；重复浏览只刷 viewed_at + 匿名不计；一组合并去重 + 占位结果等长回填。
- 两域明细/计数表查询 Helper 对「计数行不存在」以空行集按 0 收（`queryForObject` 会炸 EmptyResultDataAccess）。

### 验证

- 新测 7/7 绿（Errand 3 + Product 4 + ViewMergeWriterTest 7）；全量 bs-app 套件 368 tests, 0 failures（BUILD SUCCESS，363 存量 + 本票净增 5，见 `mvn-ticket02-full.log`；首跑 344 errors 为测试 nameserver 容器 Exit 134 的环境故障，重启后全绿）。

## Comments
