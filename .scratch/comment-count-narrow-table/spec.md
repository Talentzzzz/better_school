# spec：评论点赞计数拆窄表 + 热评排序照 feed 形态

Status: 定稿（口径来自 `map.md` 与票 01–05，全部 resolved）
Date: 2026-10-27
Scope: `backend/better-school/`（社区评论计数与热评排序）

> **本文只写形态、删什么、怎么上怎么退。** 上游决策一律不重开：票号即出处，遇到「为什么不是另一种」回票里看 `## Answer`。
> 形态经历两次用户拍板修订：票 02 把 map 原定的「两列表」收成「只抽数 like_count」（reply_count 留主行）；票 03 把热评排序升级为照帖子 feed 形态建投影索引。以本文为准。

## 索引

| 章节 | 内容 | 来源票 |
|---|---|---|
| 1 | 目的地、语义底线、边界 | map / 02 |
| 2 | 两件套 DDL（计数窄表 + feed 索引） | 02 / 03 |
| 3 | 写路径（行诞生、点赞、审核、软删、索引同步、重建 job） | 02 / 03 / 04 |
| 4 | 读路径（四条 SQL、批量回查、hot 索引序、自见口径、PO/DTO 链） | 03 |
| 5 | 交付序（建表 → 切码 → 删列、脚本、回滚、同步面清单） | 04 |
| 6 | 词条与文档 | 05 |
| 7 | 受影响的测试 | 01 / 04 / 05 |
| 8 | 残留风险 | 02 / 03 |

## 1 目的地与边界

### 1.1 换的是什么

| # | 链路 | 今天怎么走 | 换完怎么走 |
|---|---|---|---|
| 1 | 评论点赞计数 | `post_comment.like_count` 主表列，合并事务 / 取消赞同事务直 UPDATE 主行 | 同事务 UPDATE 窄表 `post_comment_like_count`；根评论旁带刷 `post_comment_feed_index` 快照 |
| 2 | 计数行存放 | 无独立行（列在主行） | 窄表行诞生于**审核通过事务**（照 post_feed_index / 市集六表「行诞生于进入可见轮」先例） |
| 3 | 热评排序 | `ORDER BY c.like_count DESC, c.id`（主列 filesort） | `post_comment_feed_index` 索引序扫描 `(post_id, like_count, comment_id)`，offset 分页契约不动 |
| 4 | 列表展示取数 | SELECT 里直取主列 | 主查询去列，页内 id 集一次 IN 回查窄表、Java 填充（照市集 viewed / feed listByIds 思路） |

**动机是形状统一，不是性能改造**：与帖子侧「计数真源窄表（post_like_count）+ 排序投影索引（post_feed_index）」两件套同构。

### 1.2 语义底线

| 项 | 一致性 | 真相源 |
|---|---|---|
| 评论点赞数 | **与关系行同事务，精确**（合并只减少事务次数，不改变可见性） | `post_comment_like` 条数 = 窄表 `like_count`；索引快照只是排序投影 |
| 根评论 feed 索引 | 同事务旁带 ±N，0 行命中落 warn 交重建 job | 窄表（索引不是真值源） |
| reply_count | **不动**：留主行、±N 同形、与审核动作同事务精确 | `post_comment.reply_count` |
| update_time | 计数写不再刷主行 `update_time`（窄表 / 索引各刷自己的）；主行无排序键依赖它 | — |
| 时间排序键 | 保持现状：根列表 `c.id`、主页 tab `create_time DESC`，零回查 | `post_comment` 主行 |

### 1.3 边界（不做）

- 不搬 `reply_count`（票 02 修订）：主行列保留，读写路径零改动。
- 不动 `post_comment_like` 关系表（事实源）、不动 `post_comment_count`（帖子评论总数窄表）。
- 不做回填：**不管存量**（票 02 用户拍板，后果——存量评论点赞显示 0、旧列删除时历史数据丢弃——已确认接受）。
- 不改 offset 分页 + total 对外契约，前端零感知（map Out of scope）。
- 语义档位不变：点赞精确、不批量化（hot-row-merge 票 04 结论沿用），本 effort 只换落点。
- 不做压测验收。

## 2 形态：两件套 DDL

### 2.1 `post_comment_like_count`（计数真源，全量评论）

```sql
CREATE TABLE IF NOT EXISTS post_comment_like_count (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    comment_id  BIGINT   NOT NULL COMMENT '评论业务 ID（唯一；根评论与子回复都入表）',
    like_count  INT      NOT NULL DEFAULT 0 COMMENT '评论点赞数（点赞合并事务/取消赞同事务直写；GREATEST(0,±N) clamp；真源 = post_comment_like 条数）',
    create_time DATETIME NOT NULL COMMENT '创建时间（= 审核通过时刻）',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_comment (comment_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评论点赞计数表（专用窄行，不与评论内容/状态写入抢锁；行诞生于审核通过事务）';
```

- 命名照 `_count` 语法族、主表全名前缀（用户拍板）；主键照 `post_like_count` 旧先例 `id + uk`（用户拍板）。
- **无二级索引**：取数全走 `comment_id` 唯一键点查；热评排序走 §2.2 索引表，本表 like_count 索引无服务对象（票 02 Q3）。

### 2.2 `post_comment_feed_index`（热评排序投影，仅根评论）

```sql
CREATE TABLE IF NOT EXISTS post_comment_feed_index (
    comment_id  BIGINT   NOT NULL COMMENT '根评论业务 ID（唯一）',
    post_id     BIGINT   NOT NULL COMMENT '所属帖子业务 ID',
    like_count  INT      NOT NULL DEFAULT 0 COMMENT '点赞数快照（点赞事务旁带 ±N，真源 post_comment_like_count，UPDATE 落 0 行落日志交重建 job；「最热」排序键）',
    create_time DATETIME NOT NULL COMMENT '创建时间（= 审核通过时刻）',
    update_time DATETIME NOT NULL COMMENT '更新时间（技术时间戳，禁止当排序键）',
    PRIMARY KEY (comment_id),
    KEY idx_post_like (post_id, like_count, comment_id) COMMENT '「最热」：单帖内索引序扫描'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '根评论热评排序投影（post_comment 的可见根行 1:1 索引，同事务直写）';
```

- PK = 业务键（feed 先例）；**无 state 列**：行集 = 当前可见根评论——过审事务 INSERT、软删事务物理 DELETE。评论软删是终态无恢复路径（触点票 ⑥），帖子侧「行常驻 + state 快照镜像」在评论这里永远只有 NORMAL，不维护（票 03 ②）。
- 子回复不入索引（不按赞排，只展示赞数 → 只进窄表）。

## 3 写路径

### 3.1 行诞生（审核通过事务）

`approveCommentWithCount`（CommunityRepository:680-693）事务内追加，根评论与子回复都执行：

```sql
INSERT IGNORE INTO post_comment_like_count (comment_id, like_count, create_time, update_time)
VALUES (#{commentId}, 0, NOW(), NOW())
```

- affected = 0 落 warn（防御性：裸 INSERT 撞漂移重复键会让整个审核事务失败；IGNORE 只可能吞重复键）。
- 根评论同事务再 `INSERT INTO post_comment_feed_index (comment_id, post_id, like_count, ...) VALUES (#{commentId}, #{postId}, 0, ...)`（此时赞数必为 0：待审态不可被赞，点赞前置检查锁 NORMAL）。

### 3.2 点赞 / 取消赞

`updateLikeCount` 换落点（合并器落库 `likeComments`（CommunityRepository:513-521，COMMENT_LIKE）与同步取消赞 `unlikeCommentWithCount`（:723-731）两个入口共用）：

```sql
UPDATE post_comment_like_count
SET like_count = GREATEST(0, like_count + #{delta}),
    update_time = NOW()
WHERE comment_id = #{commentId}
```

- **0 行命中（窄表行缺失）落 warn 交对账，不 ODKU 兜底**——ODKU 会把不变式从「行诞生于审核通过」改成「行诞生于第一次点赞」（票 02 ②）；评论量级无重建 job 之外的对账组件，对账 = 手工 SQL，warn 文案写清 commentId 与 delta。
- 两个入口都检查 affected rows；主行 `update_time` 不再被计数写刷。
- **根评论旁带索引快照**：同事务 `UPDATE post_comment_feed_index SET like_count = GREATEST(0, like_count + #{delta}), update_time = NOW() WHERE comment_id = #{commentId}`，affected = 0 落 warn 交重建 job（照帖子 `syncFeedIndexLikeCount` 同款文案）；子回复只动窄表。

### 3.3 审核驳回 / 软删

- 驳回（`rejectById`）：窄表行、索引行都不存在（PENDING 从未诞生），零动作。
- 软删（`softDeleteCommentWithCount`，CommunityRepository:696-714）：
  - reply_count 扣减、帖子评论总数扣减：**零改动**（主行 + 既有窄表 `post_comment_count`）。
  - 根评论被删：同事务 `DELETE FROM post_comment_feed_index WHERE comment_id = #{commentId}`（楼消失、行集退场；其窄表行保留——行生命周期 = 评论行生命周期，评论无物理 DELETE，赞数残留无消费方也无害）。
  - 子回复被删：索引零动作（本就不在）。

### 3.4 重建 job（照市集票 06 样板）

- `CommentFeedIndexRebuildJob`：`@XxlJob("commentFeedIndexRebuildJob")`，手动触发（xxl-job-tasks.sql 注册 trigger_status=0）。
- `CommunityRepository.rebuildCommentFeedIndex`——单事务两条语句：清空索引（`DELETE` 非 `TRUNCATE`，隐式提交会毁原子性）→ 重灌投影：`INSERT...SELECT` 行集 = `post_comment WHERE root_id = 0 AND state = 'NORMAL'`，`like_count` 取 `IFNULL(窄表.like_count, 0)`。
- 无快照多源问题（索引仅 like_count 一列、真值源唯一），重建纯兜 0 行 warn 的漂移；切码前存量库不存在索引行、由新 jar 运行期自然积累，**不需要**首建重建（照 market-feed-uv「切码前存量库 / 切码后走重建」二分的评论版）。

## 4 读路径

### 4.1 四条 SQL 的改造（PostCommentMapper.xml）

| 语句 | 改造 |
|---|---|
| R1 `selectRootPageByPostId`（:24-49） | 列清单去 `c.like_count`（reply_count 留）；**hot 分支**换索引序扫描：`SELECT comment_id FROM post_comment_feed_index WHERE post_id = #{postId} ORDER BY like_count DESC, comment_id LIMIT #{offset}, #{limit}` → 回查 `post_comment` 行（保留 bs_user / content_review JOIN，`WHERE c.comment_id IN (...)`）→ 按扫描序重排 → 批量回查计数填充（§4.2）；**默认分支**原样（`ORDER BY c.id`），只去列 |
| R2 `selectRepliesByRootId`（:62-82） | 去 `c.like_count`，其余不动（`ORDER BY c.id` 不回查） |
| R3 `selectPageByUser`（:112-123） | 去 `c.like_count`，其余不动（`create_time DESC` 不回查） |
| R4 `selectById`（:134-141） | 去 `c.like_count`；点赞/审核前置检查不依赖计数值，单行展示场景走 §4.2 回查通路 |

### 4.2 展示取数：批量回查（用户拍板，三列表 + 单行统一）

主查询后，页内评论 id 集 → 一次 `SELECT comment_id, like_count FROM post_comment_like_count WHERE comment_id IN (...)` → Java 填充 `PostCommentPO.likeCount`（缺行补 0）。楼内平铺、作者主页 tab、根列表同一通路；R4 单行即单元素回查。不选 SQL JOIN：批量回查与 feed 形态的 listByIds 同思路，且单行/批量统一（票 03 Q1）。

### 4.3 hot 档行为口径

- **热评档不自见**（用户拍板）：索引只收已过审根评论，作者待审/驳回自见评论不再出现在 hot 档（今天以 0 赞垫底参与排序）；默认档自见照旧。total 口径不变（他人可见 = 仅 NORMAL），hot 档省略自见行与 total 口径反而更一致。
- offset 分页 + total 契约不动：索引序扫描配 `LIMIT offset, limit` 在单帖楼数量级无问题。

### 4.4 PO / DTO 链（零改动验证）

`PostCommentPO.likeCount` 字段**保留**、由批量回查投影填充（ProductPO 先例）→ `CommunityRepository.toCommentEntity`（:823）→ `PostCommentEntity` → PostController 三个组装方法（:456/:472/:488）→ 三个 ResponseDTO：全链字段名与搬运零改动；`replyCount` 全链零改动。接口层注释同步约 6 处（触点票 ③ 列的 ICommunityRepository / CommentServiceImpl / CommunityRepository / CountActionEnum / IPostCommentDao）。

## 5 交付序

**单 PR 一步到位**（票 04 Q1）：schema.sql 两张新表 + 存量库 DDL 脚本 + 删列 + 重建 job 注册，本 effort 内做完；无停写窗口（旧代码不碰新表）、无双读观察期（不设开关文化）。

1. **执行序**（存量库照脚本头注释手工执行）：① 建 `post_comment_like_count` ② 建 `post_comment_feed_index` ③ 起新 jar（读窄表 + 过审造行 + 索引同步；**新 jar 启动前提是两表已在**）④ 删 `post_comment.like_count`。新库由 schema.sql 直接建全。
2. **存量脚本** `sql/backfill/comment-like-count-cutover.sql`：不幂等，头注释写上述执行序 + 「不管存量、存量评论点赞显示 0」+ 回滚口径。
3. **回滚**：不可逆点 = 删列（market-feed-uv 先例照抄）。删列前旧 jar 可无脑回滚（新表多余但无害）；删列后回滚需手工反向 DDL，历史点赞数据不回来。
4. **mapper / PO / fixture 同步面**（票 04 Answer §5 全清单）：baseColumns 去 `like_count`（reply_count 留）；`insert`（:10-14）去列；R1-R4 列清单去列；`updateLikeCount` 主表版本退役；`PostCommentPO.likeCount` 保留投影填充；新增窄表/索引 SQL（§2-§3 形状）；fixture 去列两处（AuthorProfile :260-266、LikeCountFlushHandler :77-79）、直查 SQL 换窄表两处（CommentLike :218、LikeCountFlushHandler :119）。

## 6 词条与文档

- **CONTEXT.md「热度」词条改多域**（用户拍板）：三处「最热」一个词条写清——主页 feed 热榜与评论热评 = 点赞数降序（同数按业务 ID 收敛确定序），市集两域推荐页 = 浏览 UV 降序；Avoid 段只避「热度分 / 加权 / 时间窗衰减」。
- **「点赞计数」词条补一句**（用户拍板）：落库位置各就各位（帖子 `post_like_count`、评论 `post_comment_like_count`，根评论另有 `post_comment_feed_index` 快照只服务排序、非真值源）+ 动机注记（计数落库形状与帖子侧统一）。「评论总数」词条不动（reply_count 零变化）。
- **docs/api/community.md**（用户拍板明写）：评论列表 hot 档只返回已过审根评论、待审/驳回自见评论只在默认档可见；对外字段名与数值语义不变。
- **docs/db**：`community_comment.md` 两新表 + 「已删除的列」段记 `post_comment.like_count`；`community.md` 读路径换源；`sql.md` 新表节 + 脚本登记；schema.sql 删列注记。

## 7 受影响的测试

**改动**（触点票 ④ 盘点）：

| 测试 | 改什么 |
|---|---|
| CommentLikeIntegrationTest | `commentLikeCountOf`（:218）直查 SQL 换窄表；hot 排序用例（:149-175）随索引序适配 |
| LikeCountFlushHandlerIntegrationTest | fixture（:77-79）去 `like_count` 列；`commentLikeCount()`（:119）直查换窄表 |
| AuthorProfileIntegrationTest | `insertComment` fixture（:260-266）去 `like_count` 列 |
| CommentDeleteSemantics / CommentRootListV2 | 随 hot 档不自见口径补断言；reply_count 相关断言零改动 |
| CommentThreadingIntegrationTest | API 级断言 replyCount，**零改动** |

**新增**（6 组）：

1. 行诞生：根/子评论过审后窄表行（0）与根索引行诞生；待审/驳回不诞生。
2. 点赞同步：赞/取消落窄表 + 根评论索引快照旁带 ±N；子回复只动窄表；0 行命中 warn（白盒）。
3. 软删删行：根评论软删索引行退场、楼内列表照常；子回复软删索引不动。
4. 热评档：索引序排序正确（赞数序 + comment_id 升序 tie）、offset 翻页不重不漏、热评档不自见、默认档自见照旧。
5. 重建 job：人工造漂移（删索引行 / 刷漂值）→ 重建修复 → 幂等不动点。
6. 批量回查：三列表 likeCount 填充正确、缺窄表行显示 0（存量语义）。

## 8 残留风险

- **存量点赞归零**：不管存量的直接后果，已用户确认；运营侧若有「历史热评」诉求只能重放 `post_comment_like` 明细（本 effort 不做）。
- **hot 档自见缺失**：作者在 hot 档看不到自己待审评论，语义变化已文档化（§6）；如后续反馈强烈，补尾方案（第二查拼尾）留作后续票，不在本 effort。
- **窄表 0 行命中无自愈**：warn + 手工 SQL；重建 job 只兜索引、不兜窄表（窄表缺失的根评论在重建重灌时按 0 处理，点赞一次即回正）。
- **批审事务化口子**：将来审核批量化（hot-row-merge 票 04 留口）时，窄表/索引 INSERT 随批事务走，形状已定（§3.1）不因批量化改变。
