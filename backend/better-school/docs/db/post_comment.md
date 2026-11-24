# 帖子评论表

> 业务：帖子评论（楼中楼树模型，见 [ADR-0001](../../../../docs/adr/0001-comment-tree-model.md)）+ 评论点赞 + 帖子评论计数；归属模块：post

|表名|说明|
|----|----|
|post_comment|评论主表（两级树：根评论 + 楼内子回复）|
|post_comment_count|帖子评论计数窄表（同事务同步维护）|
|post_comment_like_count|评论点赞计数窄表（计数真源，行诞生于评论发布事务）|
|post_comment_like|评论点赞关系（明细，uk_comment_user 幂等）|
|post_comment_feed_index|根评论热评排序投影（只服务「最热」序，不是真值源）|

**主键口径（uuid-biz-id 起）**：`id` 是纯内部物理自增，业务 ID 为 `comment_id`（`uk_comment_id`）；业务 ID 是应用层发号的 UUID 字符串（`VARCHAR(36)`，`UUID.randomUUID()` 在 insert 前生成、随行一次写入），与物理 `id` 永不同值、物理 id 永不对外。存量数据不迁移（删库重建），历史「插 0 回写」割接脚本已退役。

# post_comment

> 业务：帖子评论（两级树 + 软删）；归属模块：post

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键，不对外|
|comment_id|varchar(36)|UNIQUE(uk_comment_id), NOT NULL|业务 ID（UUID 字符串）：`root_id`/`parent_id`/post_comment_like 引用它|
|post_id|varchar(36)|NOT NULL, INDEX(idx_post / idx_post_state)|帖子业务 ID（外联 `post.post_id`）|
|user_id|varchar(36)|NOT NULL, INDEX(idx_user_state_created)|评论者用户 ID|
|content|varchar(500)|NULL|评论文字（可空：纯图评论时为空，与 `images` 至少一个非空）|
|images|varchar(1000)|NULL|图片 URL 列表（JSON 数组，最多 6 张，MinIO）|
|state|varchar(20)|NOT NULL, DEFAULT 'NORMAL'|NORMAL/DELETED；发布即对外可见，列表只查 NORMAL（原 `status` 列改名）|
|root_id|varchar(36)|NOT NULL, DEFAULT '0'|楼根评论业务 ID；`'0'`=根评论，回复根评论时=根评论 id，回复子回复时=楼根 id|
|parent_id|varchar(36)|NOT NULL, DEFAULT '0'|被回复评论业务 ID；根评论为 `'0'`，回复根评论时=根评论 id，回复子回复时=该子回复 id|
|reply_count|int|NOT NULL, DEFAULT 0|楼内可见子回复数（仅根评论有意义，仅 NORMAL 计入，同事务维护）|
|create_time|datetime|NOT NULL|评论时间|
|update_time|datetime|NOT NULL|更新时间|

> 已删除的列：`like_count`（评论点赞计数，comment-count-narrow-table 票 04 收口删列——真源 `post_comment_like_count`、「最热」排序快照 `post_comment_feed_index`，本行再无点赞数）。**存量点赞不搬**：不回填、不搬历史点赞（已确认接受；要「历史热评」只能重放 `post_comment_like` 明细）。存量库不迁移（uuid-biz-id：删库重建），历史割接脚本已退役。删列前旧列已在票 02 停写停读（`PostCommentPO.likeCount` 保留为投影填充字段）。

## 索引
- `uk_comment_id (comment_id)`
- `idx_post (post_id)`
- `idx_post_state (post_id, state)`：根列表按状态过滤
- `idx_root (root_id, state)`：楼内子回复查询
- `idx_user_state_created (user_id, state, create_time)`：按用户列评论（作者主页「评论」tab）

## 关联
- N:1 → post
- 自关联 → `parent_id` / `root_id` 指向本表的 comment_id（指向标签昵称由仓储回查 parent 行取，忽略其状态）
- 1:0..1 → post_comment_like_count（点赞计数真源，行诞生于评论发布）
- 1:0..1 → post_comment_feed_index（仅根评论有行，「最热」排序投影）
- 1:N → post_comment_like（评论点赞）
- 帖子总评论数读 `post_comment_count`（不在 post 行）

## 注意
- 匿名字段已移除：所有评论实名展示
- root/parent 由服务端推导，客户端传入的归属字段被忽略；展示永远两级平铺，仅 `parent ≠ root` 显示指向标签
- `reply_count` 同事务维护：子回复发布落库 +1 / 删除 -1
- 删除统一软删：用户删除转 `state='DELETED'`，行保留可审计，物理 DELETE 已废弃；**删根 = 整楼消失**（楼内子回复行不动、随楼不可达），总数扣 1+楼内可见子回复数；删子回复 = 仅隐藏该条，`reply_count` 与总数各 -1；DELETED 为终态，重复删除幂等
- 指向已删评论的「▶ 昵称」仍显示：指向昵称由仓储回查 parent 行取、忽略其状态

# post_comment_count

> 业务：帖子评论计数窄行（评论同步 +1，不再锁 post 行）；归属模块：post

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键|
|post_id|varchar(36)|UNIQUE(uk_post), NOT NULL|帖子业务 ID（一帖一行）|
|comment_count|int|NOT NULL, DEFAULT 0|评论数（只计 NORMAL 态）|
|create_time|datetime|NOT NULL|首次建行时间|
|update_time|datetime|NOT NULL|最近变更时间|

## 注意
- **写路径与点赞计数不同：本表是同事务同步写**——评论发布 / 软删 -1 时，在同一事务内 `INSERT ... ON DUPLICATE KEY UPDATE comment_count = GREATEST(0, comment_count + delta)`；发布落库即 +1
- 读路径：帖子列表/详情由仓储批量回查 `post_comment_count` 后 `COALESCE(comment_count, 0)` 投影；无行 = 计数 0
- 窄行设计目的：评论写入不再 UPDATE `post` 行（不与帖子内容/状态写入抢锁）

# post_comment_like_count

> 业务：评论点赞计数窄行（计数**真源**，精确值；根评论与子回复同形）；归属模块：post

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键|
|comment_id|varchar(36)|UNIQUE(uk_comment), NOT NULL|评论业务 ID（根评论或子回复，一评一行）|
|like_count|int|NOT NULL, DEFAULT 0|点赞数（点赞合并事务 / 取消赞同事务直写，`GREATEST(0, ±N)` clamp；真源 = post_comment_like 条数）|
|create_time|datetime|NOT NULL|首次建行时间（= 评论发布时刻）|
|update_time|datetime|NOT NULL|最近变更时间|

## 注意
- **谁写、何时造行**：行诞生于**评论发布事务**（与 `post_comment_count` +1 同一事务），根评论与子回复都 `INSERT IGNORE` 一行、赞数 0。不变式是「行诞生于评论发布」而不是「行诞生于第一次点赞」——点赞 UPDATE 命中 0 行只落 warn 交手工对账，不 ODKU 补行（补行会把不变式改坏）
- **写路径**：点赞合并器落库（`likeComments`）与同步取消赞（`unlikeCommentWithCount`）共用一条 `UPDATE ... SET like_count = GREATEST(0, like_count + delta)`，与 `post_comment_like` 关系行同事务；关系行是事实源，计数恒等于其条数（合并只减少事务次数）。计数写不刷 `post_comment` 主行 `update_time`——主行没有排序键依赖它
- **谁读**：各条读路径按页内 comment_id 集一次 `IN` 回查（见下「评论读路径」），缺行按 0
- **无二级索引**：取数全走 `comment_id` 唯一键点查；「最热」排序走 `post_comment_feed_index`——本表 `like_count` 索引给不出「单帖内按赞数排序」要的序，故不建
- 窄表行被人工删除/绕过直改无自愈：0 行警告（带 commentId 与 delta）+ 手工 SQL 对账；重建 job 只兜排序快照、**不兜本表**

# post_comment_like

> 业务：评论点赞流水（根评论与子回复通用）；归属模块：post

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键|
|comment_id|varchar(36)|NOT NULL, INDEX(uk_comment_user 首列)|评论业务 ID（根评论或子回复）|
|user_id|varchar(36)|NOT NULL, INDEX(idx_user)|点赞用户 ID|
|create_time|datetime|NOT NULL|点赞时间|
|update_time|datetime|NOT NULL|更新时间|

## 索引 / 注意
- `uk_comment_user (comment_id, user_id)`：一人一评一次，`INSERT IGNORE` 幂等；`idx_user (user_id)`
- 点赞只对 `state='NORMAL'` 评论有效（已删拒绝）；被赞通知由 case 层在点赞事务提交后尽力而为发送，按（触发者+目标）dedup 去重，取消赞不撤回已发通知，自赞不发
- 计数不回写本表：点赞数为计数窄表 `post_comment_like_count` 的 `like_count`，与本表关系行同事务精确 ±1

# post_comment_feed_index

> 业务：根评论热评排序投影（「最热」序的唯一来源）；归属模块：post

|字段|类型|约束|说明|
|----|----|----|----|
|comment_id|varchar(36)|PK, NOT NULL|根评论业务 ID（UUID 字符串；子回复不入本表；主键直接用业务键，同 `post_feed_index` 先例）|
|post_id|varchar(36)|NOT NULL, INDEX(idx_post_like 首列)|所属帖子业务 ID（「最热」按此前缀把扫描锁在单帖）|
|like_count|int|NOT NULL, DEFAULT 0|点赞数快照（点赞/取消赞同事务旁带 ±N；真源 `post_comment_like_count`，UPDATE 落 0 行落日志交重建 job；「最热」排序键）|
|create_time|datetime|NOT NULL|建行时间（= 评论发布时刻）：点赞数同值时按它**升序**收敛成确定序（发布先后；UUID 无序，业务 id 不再是时间代理，uuid-biz-id 票 03 起三键收敛）|
|update_time|datetime|NOT NULL|更新时间（技术时间戳，禁止当排序键）|

## 索引
- `PRIMARY KEY (comment_id)`
- `idx_post_like (post_id, like_count, create_time, comment_id)`：`post_id` 前缀把扫描锁在单帖、覆盖索引不回表；帖内按 `(like_count DESC, create_time ASC, comment_id ASC)` 取页——方向混合，索引序给不出完整序，排序落在「本帖那几十行」的窄范围内（`EXPLAIN`：covering index lookup + Sort），单帖楼数量级可接受

## 注意
- **行集 = 当前可见根评论**：评论发布事务 `INSERT`、根评论软删事务物理 `DELETE`。评论软删是终态、没有恢复路径，所以不像 `post_feed_index` 那样「行常驻 + `state` 快照镜像」——那列在这里恒为 NORMAL，没有维护价值。子回复不入本表（楼内不按赞排，只展示赞数）
- **不是真值源**：本表只有排序键一列快照，展示的 `likeCount` 仍取 `post_comment_like_count`——排序键与展示值分开放，快照刷偏只动顺序不动数字
- **漂移兜底**：`commentFeedIndexRebuildJob`（XXL-Job 手动触发，`trigger_status=0`）单事务「`DELETE` 清空 → `INSERT ... SELECT` 重灌」，行集 = `post_comment` 的 NORMAL 根评论、赞数取计数窄表（无行按 0）；只兜索引、不兜窄表。切码前的存量库没有本表的行，行由运行期发布自然积累——存量帖的「最热」档只见切码后发布的根评论，要补齐跑一次重建 job

# 评论读路径

comment-count-narrow-table 落地后的形状：各条读语句（根列表默认档、最热档、楼内平铺、作者主页 tab、单行）的列清单里都没有点赞数，展示值统一由批量回查填充。

|读路径|排序键|计数来源|
|----|----|----|
|根列表 new 档（`sort=new`）|`create_time ASC, comment_id ASC`（同秒按业务 id 收敛成确定序）|页内 id 集回查 `post_comment_like_count`|
|根列表 hot 档（`sort=hot`，也是不带 `sort` 时的默认）|`post_comment_feed_index` 的单帖前缀扫描 + 帖内 `(like_count DESC, create_time ASC, comment_id ASC)` 排序|同上（页内 id 集回查）|
|楼内子回复平铺|`create_time ASC, comment_id ASC`|同上|
|作者主页「评论」tab|`create_time DESC, comment_id DESC`|同上|
|单条评论（详情定位等）|—|单元素回查（同一条通路）|

- **最热档两级取数**：先在 `post_comment_feed_index` 上按帖子前缀扫出本页 `comment_id` 序，再按 id 集回查 `post_comment` 行（昵称由仓储批量回查拼装）并按扫描序重排，最后走同一条计数回查。索引行在而评论行不可见 = 漂移，跳过不补位（补位要么多查一页、要么让页大小漂移）
- **批量回查**：一次 `SELECT comment_id, like_count FROM post_comment_like_count WHERE comment_id IN (...)`，Java 侧投影进 `PostCommentPO.likeCount`，缺行补 0（票 02 之前过审的历史评论没有计数行 = 存量语义）。不用 SQL JOIN：与 feed 形态的批量回查同思路，单行/批量同一通路
- **重建 job 兜底**：快照漂移（点赞旁带命中 0 行的 warn、索引行被绕过直改、软删漏删索引行）跑一次 `commentFeedIndexRebuildJob`；它只重建排序快照。窄表缺行的根评论在重灌时按 0 处理，且**点赞不会自愈**（点赞只 `UPDATE` 窄表、命中 0 行仅落 warn）——要让计数回正得先手工补窄表行，再重跑一次重建
