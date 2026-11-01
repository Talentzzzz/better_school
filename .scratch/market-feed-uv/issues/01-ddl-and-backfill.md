# 01 · 两域新表 DDL 与存量迁移

Type: task
Status: resolved
Blocked by: 无

## Task

照 `feed-index-uv` 票 01 的先例，为闲置与跑腿两域建表并处置存量，产出可重复执行的 SQL：

1. **表形态定稿（本票要拍的决策）**：
   - 明细表**分域新增**（`product_view_history` / `errand_view_history`，PK `(user_id, product_id/errand_id)`、KEY `(user_id, viewed_at)`）还是泛化共用一张 `target_type + target_id` 表——倾向分域（不动 post 的 `user_view_history`，二级索引叶子天然携带业务 ID 免回表），票内给结论。
   - 计数加速列：照帖子用独立计数表（`xxx_view_count`，PK 业务 ID）还是复用/新增主表列——倾向独立表（帖子先例：计数写不碰主表行），票内给结论。
   - 两张索引表（倾向 `product_feed_index` / `errand_feed_index`，PK 业务 ID）：state 快照、feed_time（首次对外可见时刻，口径照帖子 = 审核记录最近一次通过、兜底 create_time）、view_count、**价格列**（product.price / errand.reward——五档排序里价格键要索引序扫描，价格必须进索引表）。索引覆盖五档排序：`(state, feed_time, id)`、`(state, view_count, id)`、`(state, price, id)` 各一条（时间升序走反向扫描）。
2. **存量迁移**：
   - 索引表回填：行集口径两域各自定（商品 = ON_SALE；跑腿 = 大厅公开口径，与票 03 的 state 快照口径一致，票内拍板并写明），feed_time 取审核记录最近一次通过（商品查 `content_review` target_type='PRODUCT'、跑腿 target_type='ERRAND'，查不到兜底 create_time），view_count 商品从 `product.view_count` 抄入、**随后清零重启**（用户拍板：PV 不换算 UV，票内明确清零语句的执行时机与「票 02 上线后」的窗口关系），跑腿无存量。
   - 明细表不回填（无按人明细可搬，UV 从上线起累计，照帖子先例）。
3. 对照 `sql.md` 的主键/命名口径检查表定义风格（DATETIME、COMMENT、无外键），有例外就在 sql.md 声明；`schema.sql` 是唯一 DDL 事实源。
4. 集成测试：直跑脚本文件钉住回填语义（行集、feed_time 口径、价格抄入、清零时机、可重跑幂等），`CommunityApiTestBase` 同款 TRUNCATE 清单补齐新表。

## Answer

### 表形态拍板

- **分域新增，不泛化**：六张新表 `product_feed_index` / `errand_feed_index` / `product_view_history` / `errand_view_history` / `product_view_count` / `errand_view_count`（`schema.sql` 市集六表节），post 三表一行不动。跑腿业务键列名沿用域内惯例 `errand_order_id`（对齐 `errand_order.uk_errand_order_id`），不短写成 `errand_id`。分域的理由照 map 倾向成立：明细表 PK `(user_id, 业务ID)` 让二级索引叶子天然携带业务 ID 免回表；泛化表省不了任何写路径，反而引入 target_type 判断分支。
- **计数加速列 = 独立窄表**（照帖子先例）：主表 `product.view_count` 不复用——计数写不碰主表行；该列退役路径：票 02 读路径换源后无读方，删列随收口票 06（本票只停不停）。
- **索引表**：PK 业务 ID 1:1 投影；列 = state 快照 + feed_time + **price** + view_count + update_time（时间列收窄照帖子例外口径）；三条排序索引 `(state, feed_time, 业务ID)` / `(state, view_count, 业务ID)` / `(state, price, 业务ID)`，时间升序走反向扫描不单设索引。`price NOT NULL`：商品 ON_SALE 必有价格（提交必填）、跑腿行只在审核通过时诞生且彼时悬赏必已支付冻结。跑腿无重提再过审（review_round 恒 1），feed_time 行诞生后不刷新。
- **主键/时间列例外已声明**：`sql.md` 头部两段例外扩容（时间列收窄例外 ② 扩为各域 feed 索引 + 明细表；主键口径例外扩为 feed-index-uv 三张 + market-feed-uv 六张），表数 68 → 74。

### 存量迁移拍板

- **行集口径**：商品 = `ON_SALE`（闲置 tab 直查同口径）；跑腿 = `PUBLISHED`——**已核实大厅前端恒传 `status: 'PUBLISHED'`**（`front/src/views/MarketView.vue:44`），这是回填行集与 feed 读路径的统一公开口径。行常驻 + state 快照的驻留口径归票 03（回填只造大厅可见行，其余状态行随流转触发点诞生/翻快照）。
- **feed_time**：`content_review` 最近一次 APPROVE（target_type='PRODUCT'/'ERRAND'）的 `MAX(create_time)`，查不到审批流水兜底主表 `create_time`——与帖子逐字同口径。
- **PV 处置（票面要求的时机拍板）**：
  - 回填首跑把 `product.view_count` 抄入 `product_feed_index.view_count`（重跑追平窗口期 PV 累加，与帖子先例同口径）；
  - `product_view_count` **不回填**：恒等明细行数的不变式从第一天成立，明细不回填则计数从空表（= 0）起步；
  - 清零 = **独立一次性脚本** `sql/backfill/market-feed-uv-view-count-reset.sql`（归零 `product.view_count` 与索引热度键、不碰 UV 三表），执行时机 = **票 02/03 切流量之前**（先清后切）。为什么不在「票 02 上线后」清：切码后索引 `view_count` 已是票 03 浏览旁路 ±N 累计出的 UV 真值，再清会把 UV 连同抄入 PV 一起抹掉且两者无法区分；切码前清只有秒级窗口的旧展示归零 + 残留微量 PV 活在将退役的列里（无读方）。
  - 回填脚本头部圈禁：**票 02/03 上线后禁止整脚本重跑**（重跑会用旧列 PV 覆盖 UV 真值），切码前重跑无此顾虑。
- 明细/计数表不回填（无按人明细可搬，UV 从切码起按 INSERT 成功精确累计，照帖子先例）；跑腿无存量浏览量，`view_count` 恒 0。

### 交付物

- `backend/better-school/sql/schema.sql`：市集六表 DDL + 头部例外注释同步。
- `backend/better-school/sql/backfill/market-feed-uv-backfill.sql`：①商品回填 ②跑腿回填 ③两域 state 收口（回填后、换代码前离开可见态的行同步快照）+ 尾部人工验证查询；可重复执行（全量重投影）。
- `backend/better-school/sql/backfill/market-feed-uv-view-count-reset.sql`：一次性清零脚本（执行时机见脚本头）。
- `sql.md`：六表节 + 头部例外/表数同步。

### 验证

- 新增 `MarketFeedUvBackfillIntegrationTest`（trade 包，4 用例）：①商品回填（ON_SALE 过滤、feed_time 最近一次通过多轮夹具、create_time 兜底、price/view_count 抄入）②跑腿回填（PUBLISHED 过滤、feed_time、reward 抄入、无存量恒 0）③可重复执行 + 重跑追平窗口期漂移（改价/PV 累加/售出/锁定）+ 幂等不动点 ④清零脚本语义（主表列与索引热度键归零、UV 三表不被触碰）。测试直跑两个脚本文件本身（剥 `--` 注释按分号切语句），不内联复制防漂移。
- `CommunityApiTestBase` TRUNCATE 清单补齐六张新表。
- 测试库重灌 schema（reset-test-db）后新测 4/4 绿；全量 bs-app 套件 363 tests, 0 failures（BUILD SUCCESS）。

## Comments
