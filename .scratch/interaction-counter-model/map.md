# map：互动计数的落库模型（增量累加器 vs Redis 覆盖）

Label: wayfinder:map

## Destination

`.scratch/interaction-counter-model/spec.md` 定稿：互动计数（帖子点赞、评论点赞、浏览量、评论总数）各自的一致性档位与落库形态，Redis 在其中的角色（累加器 / 真相 / 缓存），消息幂等靠什么扛，存量与重建对账怎么走。**只出决策与 spec，不写业务代码**；落地另起 `.scratch/interaction-counter-model-impl/`。

## Notes

- 范围：`backend/better-school/` community 域计数。`backend/marketing/` 只作为可复用件参考（outbox + `delay_level` + 合并节拍），**库存侧不改一行**。
- **先纠前提**：互动计数侧现在**没有扫表、没有 state**。现行是「点赞事务落 `post_like` + 每笔一条计数消息 → `PostCountAggregator` 200ms JVM 内存窗口聚合 → 窄表 `post_like_count` / `post_comment_count` / `post.view_count` 做 `± Δ`」。所以「复用它省掉扫表和 state」这句话对点赞域不成立——那两样本来就不在。要评的是**换不换掉 JVM 窗口 + msgId 判重**。
- 读代码/文档得到的事实：
  - `post` 行**不存** like_count/comment_count，查询靠 `LEFT JOIN post_like_count / post_comment_count` 投影（`PostMapper.xml`）——窄表已是现行形态，覆盖/累加都只是改窄表的写法。
  - 计数消息的幂等键是**生产端 msgId**，不是 `post_like` 的 uk：`like→unlike→like` 是合法序列，按关系键去重会把第三次点赞当重投丢掉（`schema.sql` 里有这段注释）。
  - `PostCountAggregator` 的 msgId 去重是**进程内 100k LRU**：不跨实例、重启即失效 → 重投会在重启后重复计入（偏高）。
  - 评论点赞数存在**评论行自身**的 `like_count` 上，「最热」直接 `ORDER BY like_count DESC, id ASC`（`IPostCommentDao`）——排序源与展示源同一个，没有第二份。
  - `CONTEXT.md`「互动计数」：近似值、**≤ 真实条数**、偏差不回补；「评论总数」是**精确值**（审核通过与软删同事务）。
  - 库存侧现成件：`outbox_message.delay_level` + MQ 延迟等级 1（≈1.0–1.1s）+ 60s 兜底（ADR-0011）；`GETSET` 类原子取走在本仓库尚未使用。
- 用到的 skill：口径票 `grilling` + `domain-modeling`；事实票 `research`。

## Decisions so far

<!-- 索引：每个已关闭票一行；细节在该票 ## Answer 里 -->

（暂无）

## Not yet specified

<!-- 见「Fog of war」：还在雾里、尚不能成票的 in-scope 问题 -->

- **反作弊与累加器**：刷浏览量/点赞风暴下，累加器会不会把一条热 key 打成热点（单 key INCR 上限、分桶计数）——取决于票 01 是否真把读/写都压到 Redis。
- **Redis 淘汰策略冲突**：若实例是 `allkeys-lru`，累加器 key 可能被当作冷数据淘汰 → 增量静默丢失。要不要 pin / 独立 db / 单独实例。
- **`PostDetailCache` 的失效时机**：现方案「点赞刷库后失效、纯浏览不失效」在 1 秒延迟下会不会让详情页点赞数比列表页更旧。
- **搜索/推荐索引里的计数**：`PostSearchDocVO` 带 likeCount，异步刷盘后谁去回写索引。

## Out of scope

<!-- 见「Out of scope」：被明确划到本 effort 终点之外的工作；已关闭，永不毕业 -->

- **库存扣减模型**：已定稿（ADR-0011），本期不评估、不回改。
- **点赞关系表结构变更**（`post_like` / 评论点赞表）：关系表是真相，不动。
- **信息流/热榜排序算法改造**：只决定排序**读哪个源**，不改权重与算法。
