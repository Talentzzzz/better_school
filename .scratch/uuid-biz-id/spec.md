# 业务 ID 全面 UUID 化 spec

> Status: ready-for-agent
> 2026-11-01 定稿。背景：审计发现 better-school 单体的全部业务 ID（user_id/post_id/comment_id/product_id/errand_order_id/room_id/msg_id 等）是「与物理自增 id 同值同事务回写」的 BIGINT（如 `ChatRepository.insertRoom` 的 `bindRoomBizId`），marketing 域虽用 SnowflakeIdGenerator 但承载类型仍是 Long/bigint。用户拍板：业务 ID 必须全局唯一、以 UUID 字符串形式存在，**决不能用 bigint/自增承载**；存量数据不迁移，删库重建。

## Problem Statement

业务 ID 现在是数据库自增序号或其同值副本。这带来三类问题：一是 ID 依赖单库自增，分库后无法继续用「与物理主键同值」的发号方式；二是对外暴露了业务规模与增长速度，且可被枚举遍历；三是同一仓库里 ID 表示不统一（单体自增 BIGINT、marketing 雪花 Long、outbox/client_msg_id 已是 UUID 字符串），开发者在 Long/String 之间来回换算。

## Solution

所有业务对象的对外唯一标识统一为 UUID 字符串：由应用层统一的 IdGenerator 生成（当前实现 `UUID.randomUUID()`，模拟雪花全局唯一；将来换真雪花实现不动调用方），落库为 `VARCHAR(36)` 列，Java 实体/PO/DTO 全链路 String。物理主键 `id BIGINT AUTO_INCREMENT` 保留为纯内部键，永不对外、永不再与业务键同值。存量数据不迁移——开发/测试库删库重建。改动对用户行为不可见（除 ID 值本身变长变无序），客户端把 ID 当不透明字符串处理即可。

## User Stories

1. As a 前端/API 消费者, I want 所有业务对象的 id 都是 UUID 字符串, so that 用统一的字符串类型处理，不遭遇 JS Number 精度丢失
2. As a 前端/API 消费者, I want 新建资源返回的 id 直接可用于后续所有引用, so that 不需要任何数字/字符串转换
3. As a 第三方集成者, I want 无法从 id 的数值推断平台发帖量、订单量等经营数据, so that 规模数据不泄露
4. As a 恶意探测者面对的系统, I want 不能通过递增 id 枚举遍历他人资源, so that 越权枚举的难度提高
5. As a 后端开发者, I want 只有一个 IdGenerator 入口生成业务 ID, so that 任何新表新接口不必关心也不必选型发号算法
6. As a 后端开发者, I want 业务 ID 在应用层随 insert 一次写入, so that 不再存在「物理自增回写业务键」的第二次写和同值副本
7. As a 后端开发者, I want 实体、PO、DTO 的 id 字段类型统一为 String, so that 不再有 Long/String 混用错配和 Boxing 陷阱
8. As a 分库实施者, I want 业务 ID 全局唯一且不依赖任何单库自增, so that 分库后发号不需要中央协调器
9. As a 分库实施者, I want 分片路由键（room_id 等）按字符串哈希路由, so that 片键路由与 ID 表示解耦
10. As a 运维, I want 开发/测试环境从 schema 脚本重建即可运行, so that 没有历史割接脚本负担
11. As a 测试开发者, I want 集成测试用返回的字符串 id 全链路引用, so that ID 类型改造被真实链路验证
12. As a 排查问题的开发者, I want 日志/工单里的 UUID 能唯一定位一条记录, so that 跨表跨库追踪仍然可行
13. As a 排序收敛机制, I want 同值收敛改用「时间 + 业务 ID」复合键, so that UUID 无序化后确定序（确定榜单、去重收敛）仍然成立
14. As a 维护文档的人, I want docs/api、docs/db、sql.md 与代码的 ID 表示一致, so that 文档不再误导下一个改造者
15. As a 热度/榜单机制的消费者, I want 热榜、最热、热度三档的排序在 ID 改造后仍稳定确定, so that 刷新页面榜单不抖动
16. As a 建会话用户, I want 会话幂等（direct_room_pair 撞键回读）在 UUID 主键下仍然收敛同一会话, so that 并发建会话不产生重复房间
17. As a 发消息用户, I want client_msg_id 幂等、timeline 引用、举报定位消息等链路在新 ID 下全部正常, so that chat 行为零变化
18. As a 跑腿用户, I want 订单引用（biz_id 复合编码、超时任务路由）在新 ID 表示下照常工作, so that 超时关单不因 ID 类型失效
19. As a 抽奖/积分用户, I want marketing 域的 bucket_id/activity_id/strategy_id/record_id/inv_id 变为 UUID 字符串后行为不变, so that 积分与抽奖链路无感切换
20. As a 未来接手真雪花发号的工程师, I want IdGenerator 是接口且 UUID 只是默认实现, so that 替换实现不动任何业务代码

## Implementation Decisions

- **ID 表示**：业务 ID 一律 `UUID.randomUUID().toString()` 形式的字符串（36 位连字符规范形），DB 列 `VARCHAR(36)`，Java 实体/PO/DTO/mapper 全链路 `String`。「雪花全局唯一」语义由 UUID 承担；定义统一 IdGenerator 接口，UUID 是默认实现，将来换真雪花（如时间趋势变体）只换实现。
- **物理主键保留**：各表 `id BIGINT AUTO_INCREMENT` 物理主键保留，作为内部聚簇/回填用，永不对外暴露、永不再赋给业务键。删除 `bindRoomBizId` 类「业务 ID 与物理自增同值回写」的路径，业务 ID 由应用层在 insert 前生成、随行一次写入。
- **非实体业务键列不动**：`outbox.msg_id`、`timeout.biz_id`（复合引用编码）等已是 `VARCHAR(64)` 的传输层幂等/路由键维持现状；它们承载的值自然变为 UUID 串。
- **SnowflakeIdGenerator 退役**：marketing 的 `SnowflakeIdGenerator.nextId()` 调用点改走统一 IdGenerator，返回 String；生成器类删除。
- **确定序收敛键替换**：原「同值按业务 ID 升序/降序（即发布时间先后）」的确定性排序（热榜 feed、评论最热档、市集推荐页等，见 CONTEXT.md「热度」词条），改为「create_time + 业务 ID」复合收敛键，保证 UUID 无序化后排序仍确定。CONTEXT.md 与 docs 同步改口径。
- **chat 域联动**：room_id/msg_id 变 String 后，direct_room_pair 撞键回读、timeline/msg_id 引用、举报经 timeline 路由、推送判重逻辑保持不变（类型换了语义不变）；分库预案文档中「片键哈希」口径改为字符串哈希。
- **搜索同步**：ES 文档（post/user 投影）的 id 字段同步为 String。
- **Schema 脚本重写**：better-school 与 marketing 的 schema.sql、init-dev.sql、init-test.sql 直接按 VARCHAR(36) 业务键重写；**不写任何割接/回填脚本**，开发/测试库删库重建。既有 backfill 割接脚本（room-list-dim-cutover.sql 等）随之退役或按新类型重写。
- **文档同步**：docs/api（id 字段类型改 string、示例值改 UUID）、docs/db、sql.md、backend/CLAUDE.md 相关约定、CONTEXT.md（业务 ID 词条、「热度」词条口径）。
- **范围覆盖**：两个后端（better-school 单体全域含 chat/moderation/notification/wallet/errand、marketing 全域）。

## Testing Decisions

- 回归闸是**最高缝合闸**：既有全量集成测试套件（better-school 全量 ~410 用例、其中 chat 47 用例；marketing 集成套件）必须全绿。测试库从重写后的 init-test.sql 重建，天然验证了「删库重建」路径。
- 在关键集成测试中增加 ID 形态断言：新建资源（注册、发帖、评论、发消息、下单、抽奖）返回的 id 可被 `UUID.fromString` 解析，且后续请求用该字符串 id 引用成功——覆盖真实链路上的类型贯通。
- 不为 ID 生成写单测工厂式用例；IdGenerator 的正确性（格式、唯一性）用一条轻量单测覆盖，其余信任集成链路。
- 排序收敛键替换由既有热榜/最热/推荐集成用例验证（它们本就断言确定序）。

## Out of Scope

- 存量数据迁移与割接脚本（删库重建）。
- 真雪花/时间趋势 ID 算法的实现（只定接口，UUID 实现交付）。
- chat-shard-ready 票 02~06 的实施本身。注意：那批票面按 BIGINT 假设书写，本改造完成后实施时需按 String 体系执行。
- 前端/front 代码适配（JS 侧 id 本就按字符串处理；若发现按数字解析的硬编码另行处理）。
- 生产环境发布流程（当前无生产数据）。

## Further Notes

- audit 依据：better-school `ChatRepository.insertRoom` 的 `bindRoomBizId` 注释「业务 ID 与物理自增同值」、marketing `PointsRepository` 的 `SnowflakeIdGenerator.nextId()`、outbox/client_msg_id 已合规三态并存，是本 spec 的问题陈述来源。
- 实施顺序建议：先立 IdGenerator 与 schema（两库），再按域换类型（user→community→chat→moderation→errand→marketing），最后文档收口。票据拆分留给 to-tickets/wayfinder。
