# 03: 内容域翻转（post_id / comment_id + 确定序收敛键）

**What to build:** 帖子与评论业务 ID 变 UUID 字符串：发帖、评论、回复、feed、点赞/收藏互动计数、热评索引、搜索投影全链路 String 贯通。关键语义变更：原「同值按业务 ID 升序/降序（即发布时间先后）」的确定序收敛搭了自增 ID 时间趋势的便车，UUID 无序后热榜 feed 与评论最热档的收敛键必须换成「create_time + 业务 ID」复合键，排序仍确定（刷新不抖动）。CONTEXT.md「热度」词条涉及的口径改动留到票 08 文档收口，代码与测试断言本票内到位。

**Blocked by:** 02

**Status:** resolved

- [x] post/comment 及其索引/投影表 DDL 业务键改 `VARCHAR(36)`，schema 脚本重写、删库重建
- [x] 发帖/评论发号走 IdGenerator，应用层随 insert 一次写入；若有「自增同值回写」路径一并删除
- [x] 热榜与最热档的确定序收敛键换为「create_time + 业务 ID」，既有榜单/最热集成用例在 UUID 主键下仍全绿
- [x] 搜索投影 postId String 化并同步写入路径
- [x] 定向测试通过；全量测试全绿
