# 03: bs 点赞 / 取消赞 / 评论赞 换同步合并写

**Spec:** `.scratch/hot-row-merge-l2w256/spec.md` §3.1、§3.4、§2.6；判据见 `acceptance.md` C1 / C2 / C6

**What to build:** 帖子点赞、取消赞、评论点赞三条入口从「同事务 outbox → MQ → 200ms 聚合」换成「提交给合并器 → 等窗口 → 一组一事务落库 → 返回」，接口返回成功时计数**已经精确**，不需要等待、不需要事后重算。

落库是「关系行批量写 + 计数一条 `UPDATE`」两组语句：点赞 `INSERT IGNORE` 关系行 + 计数 `ON DUPLICATE KEY UPDATE like_count = like_count + N`（窄表只对有赞的帖子建行）；取消赞 `DELETE … WHERE post_id = ? AND user_id IN (…)` + `GREATEST(0, like_count - N)`；评论赞 `INSERT IGNORE` 关系行 + 主表行 `like_count + N`（评论计数在主表行上，没有窄表）。

批内部分撞唯一键要能定位到**具体请求**：`INSERT IGNORE` 的 affected 只给行数，撞键时回查该批的 `(post_id, user_id)`，只把 `DUPLICATE` 回给命中的那几个。`DUPLICATE` 与「取消赞但不存在」都是业务结果不是异常，`likePost` / `unlikePost` 的 HTTP 语义一个字不改。

降级全在这层：缓冲满 / 停机中 → 调用方线程用**同一个落库方法**扣组大小 1（两套 SQL 迟早漂移，而漂移只在压力最大时暴露）；等超时 → `RETRYABLE`。

**Blocked by:** 01

**Status:** resolved

- [x] 点赞返回成功时 `COUNT(post_like WHERE post_id = ?)` == `post_like_count.like_count`，**无需等待**（C1；`PostCountMergeIntegrationTest` — 原名 `PostCountAggregationIntegrationTest`，按 acceptance §4.2 更名，全 3 条按新形状重写，断言里不再有轮询）
- [x] 取消赞后计数回落到关系行条数，且不为负（`GREATEST(0, …)` 兜底，语义仍是精确）
- [x] 评论点赞仍是 ±1、唯一键幂等、可取消再赞（C2 六条保持绿）；重复点赞由唯一键裁决、不重复计（C6，`CommentLikeIntegrationTest#重复点赞不重复计`）
- [x] 批内部分撞键能精确定位：回查该批 `(post_id, user_id)`，命中者回 `DUPLICATE`，其余照常成功（`LikeCountFlushHandlerIntegrationTest` 三条：点赞撞键 / 取消赞 NOT_FOUND / 评论赞幂等）
- [x] 事务失败 = 整体回滚，调用方拿 `RETRYABLE`；计数不可能与关系行脱节，因此**不引入任何校准 / 对账逻辑**（`CountMergeWriterTest#flushFailureIsRetryable`；落库方法用 `TransactionTemplate` 把两条语句收在一个事务里，边界不出仓储）
- [x] 降级三条走通：缓冲满 / 停机中 → 同一落库方法组大小 1；等超时 → `RETRYABLE`；`REJECTED` 不返回给用户（`CountMergeWriterTest` 六条：透传 / 缓冲满 / 停机 / 超时 / 落库失败 / 降级也失败）
- [x] `shardKey` 只从行主键取：点赞 / 取消赞用 `postId`，评论赞用 `commentId`；合并器不解析 payload
- [x] 本票**不删**计数侧的 MQ 骨架（浏览仍走消息，删它需要 04）

## 落地记录

- **落库方法放在 `CommunityRepository` 上**（实现 `ICountFlushHandler`），不另立一个 handler 类：`backend/CLAUDE.md` §1/§3 要求「事务在仓储实现内」，另立类会让社区域出现第二个持 `TransactionTemplate` 的写入者。代价是 `CountMerger` / `CountMergeWriter` 与仓储构成一个**出口 = 调用方**的环，两处 `@Lazy` 已就地写明原因（出口只在 flush / 降级那一刻才需要）。
- **`CountMergeConfig` 的 `@Bean` 方法改名 `countMergeConfig` → `mergeConfig`**：类名首字母小写与 @Bean 方法同名，Spring Boot 关闭 bean 覆盖后上下文直接起不来（票 01 引入、全量套件当时没跑，本票第一次起上下文才暴露）。
- **详情缓存的旧值窗口**：点赞不再失效 `PostDetailCache`（那条 evict 本来挂在聚合器上），票 05 把「计数不进详情缓存」做完之前，详情页的 `likeCount` 可能读到缓存里的旧值；C1 的判据只看关系行与窄表，详情断言留给 C5。
- 评论**取消**赞仍走原来的同事务精确 -1（spec §3.1 的 SQL 清单只有 `COMMENT_LIKE`，`CountAction` 也没有 `COMMENT_UNLIKE`）。
