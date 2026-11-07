# Spec：chat 域成员表双维度拆分（user_room + room_member 名册 + 预览读时现算）

Status: ready-for-agent

> 本 spec 由 2026-11-07 会话讨论收口而来。对话中推演过的备选方案（预览落 room 表、OpenIM 纯实时路线、peer_id 盖章、peer_read_seq 副本）的结论已吸收进 Implementation Decisions / Further Notes，不再作为开放选项。

## Problem Statement

chat 域的现状是：`room_member` 一张表同时装着两种维度的状态——「我这个用户看到的这个会话」（已读水位、软删）和「这个会话的事实」（最后消息预览三件套副本）。这带来四个问题：

1. **语义错位**：预览三件套是会话级数据（同一会话两行恒同值），住在用户维度的行上，靠「一条 UPDATE 刷两行」的实现细节兜住一致性，表名与内容名实不符；
2. **副本同步负担**：预览是 message 真相之外的第二份事实，发送/撤回路径都要维护它，且「预览与排序键成对」的一致性完全依赖写路径纪律；
3. **分库地基缺一块**：chat-shard-ready 预案承诺「列表/未读/补拉 user 片单分片」，但「对家/成员是谁」在分库后无处就地可取（对家 id 既不在我的成员行上，也没有会话维度名册表）；1v1 靠「peer_id 盖章」能凑合，群聊场景推不出来；
4. **命名口径乱**：`last_read_seq` / `last_msg_at` 与库内其他 `*_time` / 简名风格不一致，且「member」一词被用户维度表占用，将来名册表无正名可用。

## Solution

把一张表拆成两张各归其位的表，并顺手完成预览的去副本化：

- **user_room（用户维度，将来按 user_id 分片）**：每用户每会话一行，只装 per-user 状态——排序键 `last_msg_time`、已读水位 `read_seq`、软删 `hidden_at`。预览两列（`last_msg_type`/`last_msg_preview`）删除，预览改为读时按 message 真相现算（B 方案）；
- **room_member（会话维度名册，将来按 room_id 分片）**：每会话每成员一行，装「这个会话有谁」——`room_id`、`user_id`、`join_seq`（入群水位）。1v1 下恒两行，群聊上线时即扇出名册与成员展示的地基，无需再改表；
- **user_pair_room 原样保留**：1v1 建会话查重的唯一数据库级仲裁者（`uk_pair` 撞键回滚），无任何替代；
- **不引入 peer_read_seq**：已读回执默认直接查对方 user_room 行（带 user_id 片键精确跨片点查），副本留作性能后备开关，预案中只记触发点不落列。

对使用者完全不可见：会话列表排序、未读数、预览文案、撤回弹回、软删行为全部不变；接口契约零变化。

## User Stories

1. As a 收消息用户, I want 会话列表按最后消息时间正确倒序排列, so that 最近联系人排在最前
2. As a 收消息用户, I want 列表预览始终显示该会话真实最新一条消息的类型标签与文案, so that 不点开也能知道大概内容且预览永远不会是旧账
3. As a 收消息用户, I want 列表页未读数字准确（= 会话最新序号 − 我已读水位）, so that 不漏看消息
4. As a 发消息用户, I want 我发出的消息即时出现在对方列表顶部并推进其未读, so that 对方能及时看到
5. As a 撤回消息的用户, I want 对方列表预览立即显示「[已撤回]」, so that 撤回语义在列表上同样成立
6. As a 被强制撤回场景覆盖的审核流程, I want 强制撤回后列表预览同样变为「[已撤回]」, so that 违规内容不留痕迹
7. As a 删除了会话的用户, I want 该会话从我的列表消失但对方的仍在, so that 软删只影响我自己的视角
8. As a 删除了会话的用户, I want 对方再来新消息时会话弹回我的列表, so that 不因删过会话而漏接
9. As a 打开会话的用户, I want 未读数清零（read_seq 推进到 room.last_seq）, so that 已读状态准确
10. As a 查看「对方已读」的用户, I want 已读回执按对方的真实 read_seq 判定, so that 回执是真源而非副本
11. As a 与新人建会话的用户, I want 重复点击「私信联系」只得到同一条会话, so that 并发/重复入口不会建出重复会话
12. As a 与新人建会话的用户, I want 建会话时双方的 user_room 行与名册行同时建立, so that 双方列表立即可见这条会话
13. As a 后端维护者, I want user_room 行上只有 per-user 状态, so that 表语义名实相符、不再依赖「一条 UPDATE 刷两行」保一致
14. As a 后端维护者, I want 预览只有 message 一份真相、读时现算, so that 不再有副本漂移类 bug 的存在空间
15. As a 后端维护者, I want 名册表 room_member 按 room_id 维度独立存在, so that 扇出名册/成员展示/已读统计有正式查询面
16. As a 分库实施者, I want user_room 贴 user_id 片键、room_member 名册贴 room_id 片键, so that 两维度各自单分片查询、互不广播
17. As a 分库实施者, I want 名册行带 join_seq 入群水位, so that 群聊上线时新成员可只从入群序号之后收消息
18. As a 分库实施者, I want 预案片键表更新为 user_room（user_id）/ room_member 名册（room_id）, so that 分库日只剩 sharding 配置与回填
19. As a 集成测试维护者, I want 既有 chat 集成测试族翻到新表名新列名后语义不变, so that 行为等价有既有断言背书
20. As a 文档读者, I want sql.md / chat.md / schema.sql 注记全部按新表名新列名重写, so that DDL 唯一事实源与文档不再有两套口径

## Implementation Decisions

- **DDL（唯一事实源 `sql/schema.sql`）**：
  - 现 `room_member` 改名 **`user_room`**，删 `last_msg_type` / `last_msg_preview` 两列，`last_read_seq` → **`read_seq`**，`last_msg_at` → **`last_msg_time`**；保留 `id` 物理主键；唯一键收口为 `uk_user_room (user_id, room_id)`（片键前置），排序列表索引 `idx_user_msg (user_id, last_msg_time)` 原语义换名；
  - 新增名册表 **`room_member`**：`id`、`room_id`（片键语义，唯一键首列）、`user_id`、`join_seq BIGINT NOT NULL DEFAULT 0`（入群水位：1v1 建会话时为 0，群聊上线即「从哪条 seq 起收」）、`create_time`、`update_time`，唯一键 `uk_room_user (room_id, user_id)`。1v1 下每会话恒两行；
  - `user_pair_room` 结构零改动；
  - 不新增 `peer_read_seq`。
- **未读数算式**：`room.last_seq − user_room.read_seq`，服务端拼装不变；markRead 仍带 `read_seq < 目标` 只前进守卫。
- **预览读时现算（B 方案）**：列表拼装在拿到 room 行的 `last_seq` 后，按 `(room_id, last_seq)` 批量点查 message 最新一条，渲染规则：`RECALLED`→[已撤回]、`IMAGE`→[图片]、`CARD`→按 `ref_type` 出 [商品]/[帖子]/[跑腿]（不查标题）、`TEXT`→原文截 200。渲染逻辑落领域/应用层拼装处，不进 SQL。
- **读路径（SQL 层零 JOIN 纪律不变）**：① user_room 分页（筛+排+取页，`idx_user_msg`）→ ② room 批量回查（`last_seq` 未读真源 + source/initiator 角色锚点）→ ③ message 批量点查现算预览（新增段）→ ④ 名册批量回查对家 user_id（替代原 `selectPeerMembers` 对成员表的 `user_id != 我` 过滤）。
- **写路径（发送事务）**：room 取号 → message 插入 → timeline×2 扇出 → **user_room×2 只刷 `last_msg_time`**（不再刷类型/预览）。撤回路径**不再有任何 member/user_room 预览维护**——「[已撤回]」由读时现算天然覆盖，写路径净减一处。
- **建会话事务（单体形态）**：user_pair_room 闸门先行查重 → room + 两行 user_room + 两行 room_member 名册 + 一行配对同事务写入；并发撞 `uk_pair` 整体回滚后幂等回读既有 room_id，行为不变。分库形态（pair 先行、room 懒物化、其余异步建）维持 chat-shard-ready 预案口径，本票不实施分库。
- **分库预案注记**：片键表更新为 `room_id` → room/message/**room_member(名册)**，`user_id` → **user_room**/timeline/user_seq；`peer_id 盖章`与`peer_read_seq 副本`两个旧预留记为**作废/降级**（名册表结构性替代前者；后者降级为触发点清单中的后备开关，不落列）。
- **代码面**：PO 改名随表（用户维度 PO 带 UserRoom 语义，名册 PO 承接 RoomMember 名）；仓储接口与实现、mapper XML（含 `<sql>` 列口径注释）、发送/撤回/已读/列表服务层全部随动；接口契约（HTTP/WS）零变化。
- **文档面**：`sql/schema.sql` 头注记与两表注释、`sql.md` chat 段、`docs/db/chat.md`（room_member 段重写为两张表 + message 段预览渲染规则迁注）、docs/db README 表数口径、CONTEXT.md 相关词条、mapper 内口径注释，全部对齐新名。

## Testing Decisions

- **缝（seam）**：沿用既有最高缝——chat 域 HTTP/WS 集成测试族（发送管线/读路径/持久化/撤回举报四类），断言外部行为（列表排序、未读、预览文案含撤回态、弹回、幂等建会话），不直调 consumer、不断言内部实现。既有断言集就是「行为等价」的判据，本票不新增缝。
- **测试改造**：既有集成测试与相关夹具（建资源 helper、SQL 字面量）翻到新表名新列名；预览相关断言**增强一处**——「撤回后不经过任何预览写路径、列表读时直接呈现 [已撤回]」（B 方案新行为）。
- **实跑豁免（用户拍板）**：本票**不起环境跑测试**。交付闸门为：better-school 全模块 `mvn compile` + `test-compile` 全绿 + 不依赖 Docker 的纯单测全绿；集成测试族改造到位但留待下一次全量回归窗口统一实跑（uuid-biz-id 票 07 先例）。风险随票注记：SQL 字面量与 mapper XML 只有集成测试能兜，实跑前该票不得标记为「已验证行为等价」，只可标记「编译与单测已验证」。

## Out of Scope

- 群聊功能本体（名册表只预埋结构，不做群业务）；
- 分库实施与 sharding 配置落地（chat-shard-ready 票 06 的领域）；
- `peer_read_seq` 及任何已读回执读优化（默认直接查真源）；
- 预览渲染能力增强（如 CARD 查标题）；
- 前端任何改动（接口契约零变化）；
- 存量数据迁移（uuid-biz-id 起口径为删库重建，无割接脚本）。

## Further Notes

- 与 `.scratch/chat-shard-ready/` 的关系：该 effort 票 01（DDL 收口，列表职责迁 room_member）已 resolved，本票**取代其历史方向**——列表职责落两处（user_room 排序键 + 读时预览），并补上其未写明的「对家 id 分库后从哪来」缺口（名册表结构性关闭）。分库预案票 06 的片键表与读路径清单需按本票口径改写后再 ready-for-agent。
- 本票落地后，OpenIM 对照结论更新为：与「Conversation（用户维度，预览现查）+ GroupMember（名册）」双表结构同构。
- 讨论中判定为「偏好级、不值得单开票」的旧方案（预览全落 room、纯 OpenIM 实时路线不拆名册）不再回退；若未来群聊上线引发写扩散系数问题，重开讨论的是「预览/排序键维护方式」，不是本票的表结构。
