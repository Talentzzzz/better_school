# 01 · 三张新表 DDL 与存量迁移

Type: task
Status: resolved
Blocked by: 无

## Task

按 map Notes 的定稿建表并退役旧列，产出可重复执行的 SQL：

1. `post_feed_index`（PK post_id；KEY `(state, feed_time, post_id)`、`(state, like_count, post_id)`）
2. `user_view_history`（PK `(user_id, post_id)`；KEY `(user_id, viewed_at)`）
3. `post_view_count`（PK post_id）
4. 存量迁移：
   - `post_feed_index` 回填：NORMAL 帖插入（feed_time 取审核记录里该帖最近一次通过时刻；查不到审批流水的取 `create_time` 兜底），like_count 从 `post_like_count` 抄入
   - `post_view_count` 回填：从 `post.view_count` 一次性搬数
   - `post.category`、`post.view_count` 列**本票只停写不删列**（删列放最后一票收口时）
5. 对照 `sql.md` 的主键/业务 ID 口径检查表定义风格（DATETIME、COMMENT、无外键）

## Answer

### 交付物

- **DDL（`backend/better-school/sql/schema.sql`，唯一 DDL 事实源）**：三表按 map 定稿形状落地；风格对照 sql.md——DATETIME、逐列 COMMENT、无外键、utf8mb4/InnoDB。
- **主键口径例外**（sql.md 头部主键口径段已同步声明）：三表是投影/明细/加速列、不设独立业务列，主键直接用业务键——1:1 投影与计数表 ODKU upsert 直达（撞 PK 即翻行），明细表二级索引叶子天然携带 post_id，feed「出 ID → 回查组装」的读路径免一层回表。
- **时间列收窄**（sql.md 命名口径例外 ② 已声明）：`post_feed_index` 只留 `update_time`（`feed_time` 承担行诞生时刻）；`user_view_history` 只留 `viewed_at`；`post_view_count` 惯例双列收尾。
- **存量回填**：`backend/better-school/sql/backfill/feed-index-uv-backfill.sql`（不自带 USE，库名由命令行给，便于喂给 better_school_test 验证）。三段：
  1. NORMAL 帖回填 `post_feed_index`：feed_time = `content_review`（target_type='POST' AND action='APPROVE'）的 MAX(create_time)（重提再通过取最近一次）；查不到审批流水的老帖取 `post.create_time` 兜底；like_count 从 `post_like_count` 抄入（无行 = 0，与运行期窄表懒建行口径一致）。
  2. state 收口：回填后、换代码前离开 NORMAL 的帖子，把索引行同步成 `post.state`（不可见化）——为第 1 段的 NORMAL 过滤补的窗口漂移修补，运行期这一步由票 03 的状态流转事务同事务完成。
  3. `post_view_count` 从 `post.view_count` 搬数：只搬 >0 的行（0 浏览不建行，懒建行口径），**不看 state**（计数与可见性无关）。
- **可重复执行语义**：全部语句 `ON DUPLICATE KEY UPDATE` 全量重投影，重跑 = 追平回填后旧代码仍在写旧表的窗口期漂移。**已知豁免（票 02 上线后禁止整脚本重跑）**：`post.view_count` 届时停写冻结，重跑会用旧值覆盖 UV 真值（脚本头注释圈禁，无机制护栏——切换窗口由人盯；`post_feed_index` 段无此顾虑，重投影即重建）。另注：重跑会把 feed_time 重刷为「最近一次通过」，与票 03 测试口径（重提再通过 → feed_time = 第二次通过时刻）一致；map Notes「此后不刷新」按该口径理解（其余写路径不碰 feed_time）。
- **`user_view_history` 不回填**：历史上只有 `post.view_count` 聚合值、没有按人明细，UV 从本票起以 INSERT 成功为准，存量浏览不追溯（计数从 0 起步）。
- **`post.category` / `post.view_count` 只停写不删列**：本票 DDL 未触碰两列；停写分别落在票 02（删 `increaseViewCount`）与票 06（category 退役链路），删列在票 07 收口。

### 验证

- 新增 `FeedIndexUvBackfillIntegrationTest`（2 用例）：① NORMAL 过滤 + feed_time 取最近一次通过（多轮审核直插夹具）+ create_time 兜底 + like_count 抄入 + view_count 搬数（不看 state、0 不建行）；② 重跑追平窗口期漂移（点赞涨/帖离开 NORMAL/浏览涨）+ 幂等不动点。测试直跑脚本文件本身（剥 `--` 注释按分号切语句逐条执行），不内联复制语句防漂移。
- `CommunityApiTestBase` TRUNCATE 清单补齐三张新业务表。
- 测试库重灌 schema（init-test.sql + schema.sql）后单测 2/2 绿；全量 bs-app 套件 **343 tests, 0 failures**（BUILD SUCCESS）。
- code-review（standards + spec 双轴）发现 sql.md / schema.sql 头部「命名口径唯一例外」段未同步本票例外——已修正（例外扩为两处：timeout 分表 + 本票两表）；spec 轴确认三表形状、回填语义、只停写不删列均与票面一致。
