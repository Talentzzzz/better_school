# chat 域分库就绪重构 spec

> Status: ready-for-agent
> 2026-11-01 定稿。决策论证过程见 `.scratch/chat-refactor/research/05-业界IM存储方案对比与库表设计评估.md`（8 个 IM 系统源码/文档级调研）及本轮对话推演。本 spec 是 chat 域「分库就绪」改造的唯一需求来源。

## 0. 目标与范围

- 动机：chat 域将以大型系统为目标做**分库**（用户拍板），现库表设计存在三处经调研确认的问题，需在单库时代提前收口，把分库日的「协议重写工程」降级为「贴片键 + 数据回填」。
- 三个问题（调研 §10 结论）：
  1. **会话列表数据放错维度**：`room.last_msg_type/preview/at` 在会话维度行上，用户维度列表要两段式 + 应用层排序；凡服务端管会话列表的系统清一色用户维度（OpenIM Conversation 行、野火 user_messages 派生、腾讯云 recentcontact）；
  2. **发消息「事务内扩散」假设单库**：分库后 message 在 room 片、timeline/user_seq 在收件人 user 片，跨片事务物理不可能（业界零先例：微信结构上不可能单事务、OpenIM 异步流水线、Tablestore 两库两写）；
  3. **timeline 只增不删**：写扩散派全线都有收件箱退场机制（Signal 30 天 TTL、Tablestore 同步库 7 天、野火队列上限），现状无清理策略。
- 路线选择：**保留 timeline 写扩散**（用户定稿，微信/野火/Signal/Tablestore 同阵营），否决 conv 水位读扩散方案（timeline 的回放审计、全局游标单点簿记被判定值得保留）。
- **Out of scope**：实际分库迁移（sharding.yaml 片键配置、数据重分布——只重写预案文档）、群聊、ES 消息检索、peer_read_seq 副本、通知域、客户端协议改动。

## 1. Solution（用户视角）

发消息仍然秒达；会话列表、未读数、预览与现状无感差异；断线补拉、多端已读、撤回、弹回等全部行为不变，客户端零改动。变化对用户不可见，对分库实施者：库表天然可贴片键，发消息从「单库大事务」变为「room 片本地事务 + 可靠异步扩散」，扩散失败不丢不重、可对账。

## 2. User Stories

1. As a 发消息用户, I want 消息发出后立即收到成功确认, so that 不必等待对方收件箱落库才知道发送结果
2. As a 发消息用户, I want 发送失败时用同 client_msg_id 重试收敛同一条消息, so that 网络抖动不会产生重复消息
3. As a 收消息用户, I want 会话列表按最后消息时间正确排序, so that 最近联系人排在最前
4. As a 收消息用户, I want 列表预览显示最后一条消息的类型标签与文案, so that 不点开也能知道大概内容
5. As a 收消息用户, I want 未读数 O(1) 且准确, so that 角标不遗漏不错加
6. As a 收消息用户, I want 新消息弹回我隐藏掉的会话, so that 软删的会话不吞新消息
7. As a 离线后回来的用户, I want 按 delivery_seq 游标增量补拉, so that 断线期间的消息一条不漏
8. As a 离线很久（超过 timeline 保留期）的用户, I want 通过会话水位逐会话兜底拉取, so that 收件箱清理不造成永久丢消息
9. As a 多端用户, I want 已读水位线在多端共享, so that 一端已读另一端角标同步消退
10. As a 撤回消息的用户, I want 撤回后对方列表预览变为「[已撤回]」, so that 撤回语义在列表上同样成立
11. As a 被强制撤回的审核员, I want 举报强制撤回同样刷新列表预览, so that 违规内容不留痕迹
12. As a 举报用户, I want 举报 MESSAGE 时系统能定位到消息正文, so that 举报链路不因分片失效
13. As a 建会话用户, I want 同一对人的重复建会话请求幂等收敛到同一会话, so that 并发建会话不产生两条房间
14. As a 私信联系商品/跑腿卖家的用户, I want 会话来源（ERRAND/PRODUCT）被记录, so that 会话卡片能溯源
15. As a 收消息用户, I want 推送与补拉按 (room_id, seq) 判重合流, so that 同一条消息不重复弹
16. As a 运维, I want timeline 有明确的保留期与清理任务, so that 收件箱不无限膨胀拖垮扫描与存储
17. As a 运维, I want 扩散失败有 outbox 重试与积压告警, so that user 片故障时消息最终送达且可观测
18. As a 分库实施者, I want 每张表有明确的片键与写路径形态说明, so that 分库日只需配置路由 + 回填，不再重新设计
19. As a 分库实施者, I want room_member 的副本字段只由消费链路单一写入方维护, so that 副本漂移有唯一对账方向
20. As a 开发者, I want 发消息的强一致核（取号+内容）圈在同一个片的事务里, so that seq 无空洞、幂等键可靠这些正确性属性不依赖分布式协调

## 3. Implementation Decisions

### 3.1 DDL（6 张表，2 张动、4 张不动）

| 表 | 变更 | 说明 |
|---|---|---|
| `room` | **删** `last_msg_type/last_msg_preview/last_msg_at` 三列 | 剩取号器 `last_seq` + `state` + `source/initiator_id`；预览职责迁出 |
| `room_member` | **增** `last_seq_copy`（GREATEST 推进）、`last_msg_type`、`last_msg_preview`、`last_msg_at` 四列；`idx_user` 换 `idx_user_msg (user_id, last_msg_at)` | 升级为「用户维度会话列表唯一查询面」；与 OpenIM Conversation 行同构 |
| `message` | 不动 | 内容真源，`uk_room_seq`/`uk_room_client`/`uk_msg_id` 原样 |
| `timeline` | 不动 | 收件箱 + 全局游标载体，只插不改 |
| `user_seq` | 不动 | delivery_seq 发号器，行锁 ODKU（量级不需要号段，预案不预留发号器） |
| `direct_room_pair` | 不动 | 建会话幂等裁决者 |

- 未读数算式改为 `room_member.last_seq_copy − last_read_seq`（user 片就地可算；真源 `room.last_seq` 仍由取号事务维护）。「打开会话推到 last_seq」改为推到 `last_seq_copy`。
- 存量回填：从 `message` + `room` 现值回填 `room_member` 四列（一次性割接脚本，非幂等，头注释含执行序）。

### 3.2 发消息链路拆分（本 spec 的核心行为变化）

- **room 片本地事务**（唯一强一致核）：`room.last_seq` 行锁取号 + `message` 插行（含 `uk_room_client` 幂等）+ **outbox 行**（扩散载荷）。
- **异步扩散消费端**（outbox → RocketMQ，按 user_id 分队列保序）：每个收件人一个 **user 片单片事务** = `timeline INSERT` + `room_member UPDATE`（`last_seq_copy = GREATEST(...)`、刷 `last_msg_*` 三件套、清 `hidden_at`）+ `user_seq` ODKU 取 `delivery_seq`。
- 幂等与判重语义全部不变：`uk_user_delivery` 吸收重投、`(room_id, seq)` 推送判重、`delivery_seq` 游标补拉。客户端零改动。
- 发送方确认只依赖 room 片事务；扩散滞后毫秒级且只影响展示字段（预览/未读/角标），可感知路径（WS 推送）不经过扩散落库时序。

### 3.3 其他写路径的副本维护

- **撤回**：复用既有撤回通知 outbox 链路，消费端顺手把两端 `room_member.last_msg_preview` 刷为「[已撤回]」。原则：**room_member 副本字段只由消费链路单一写入方维护**（不做同事务直写，保证分库日单一写入方已成立）。实施票需确认现行撤回推送是否已走 outbox，未走则补。
- **建会话**：单库时代保持现状（room + 2 room_member + pair 同事务）；分库预案记录最终形态 = pair 事务先行（幂等裁决）→ room 行首条消息 `INSERT ... ODKU` 懒物化 → 两行 room_member 消费端异步建。
- **已读**：`last_read_seq` 推进单行不变；分库后「对方已读」= 按 peer_user_id 跨片精确点查（可路由），不做 `peer_read_seq` 副本（触发点预留）。
- **举报 MESSAGE**：经举报人自己的 timeline 行取 `(msg_id → room_id, seq)` 再路由取正文，避免按 `uk_msg_id` 广播。

### 3.4 timeline 清理（新增义务）

- 保留期 30 天（可配置），XXL-Job 清理任务按期删除。
- 超期离线客户端兜底协议：补拉返回的首行 `delivery_seq` 与游标不连续（缺口）→ 客户端检测到缺口 → 改按 `room_member.last_seq_copy` 逐会话拉缺口（读扩散式兜底，仅长尾场景）。
- 与业界对齐：Signal 30 天、Tablestore 同步库 7 天、野火队列上限、本项目补拉 200/500 上限。

### 3.5 分库预案（文档重写，本 spec 不实施）

| 片键 | 表 | 写路径 |
|---|---|---|
| `room_id` | `room`、`message` | 发消息 = 唯一强一致事务（单片） |
| `user_id` | `room_member`、`timeline`、`user_seq` | 全部由扩散消费端写，最终一致 |
| `user_min` | `direct_room_pair` | 建会话先行事务 |

- 预案必须废除「发消息单事务」假设：业界零跨片强一致先例，正确性 = 片内事务 + outbox 可靠投递 + 幂等消费 + 游标对账。
- 读路径：列表/未读/补拉全部 user 片单分片；历史按 `room_id` 点路由；「已读回执」跨片精确点查；`msg_id` 单键查正文走举报人 timeline 路由。
- 参照：本仓库 `timeout_task` 分表先例（`dbCount=1` 硬约束、ShardingSphere hint、逻辑表名纪律）；野火 `t_user_messages_0..127` 按 uid 分表是同一路线的业界先例（单实例内）。

## 4. Testing Decisions

- **测试缝（唯一）**：HTTP/WS 集成测试——走发消息/列表/补拉/撤回等对外接口断言外部行为，沿用 chat-impl 票 07 的集成测试风格。不直调扩散 consumer、不断言内部实现细节。
- **异步确定性**：新增测试助手 `awaitDiffusion`——有界轮询等待消费完成（如轮询列表副本推进/timeline 行出现，超时即败），使异步链路在测试中可确定性断言。
- **好测试的标准**：只断言外部可见行为（列表排序与预览、未读数、断线补拉完整性、弹回、撤回预览、幂等重试收敛），不碰表内部状态。
- **回归基线**：现行为不变的用例（发送→列表/未读/补拉/推送判重）先行，重构前后同一套用例全绿。
- **新增用例**：
  - 发送成功 → 两端列表副本推进、未读正确、预览正确（awaitDiffusion 后断言）；
  - 扩散消费失败重投 → 幂等不重不漏（`uk_user_delivery` 吸收）；
  - timeline 清理后补拉 → 客户端可检测 delivery_seq 缺口 → 逐会话水位兜底拉取成功；
  - 撤回 → 两端预览变「[已撤回]」；
  - 建会话幂等（并发撞 `uk_pair` 回读）回归。
- **先例**：chat-impl 票 07 ws-integration-tests、outbox 消费类测试（计数聚合链路）。

## 5. Out of Scope

- 实际分库：sharding.yaml 片键配置、数据重分布、`dbCount > 1` 启用——本 spec 只重写预案文档与完成代码就绪；
- 群聊（写扩散 ×N 是独立议题）、多设备互踢以外的多端改造；
- ES 消息检索（message 的 participants 投影属未来 effort，检索/展示层职责，同步真源永远在 MySQL）；
- `peer_read_seq` 副本（点查够用，触发点预留）；
- 通知域、feed/计数等其他域一行不动。

## 6. Further Notes

- **决策记录**：conv 水位（读扩散）方案被完整推演后由用户否决——三个输点（回放审计、全序流归 ES、客户端两层簿记）中前两个被判定可接受但用户仍选 timeline 阵营，尊重产品判断；本 spec 按写扩散派收口。
- **微服务类比收敛**：跨片原子性的工具箱（2PC/Saga/TCC/Outbox）逐一套过后收敛于 outbox——Saga 补偿不成立（消息不可删）、TCC 无物可冻结（收件箱是追加非预留）、2PC 业界无人用。本仓库跨服务一致性既定纪律（本地消息表 + 幂等重试）从跨服务搬到跨片。
- **MQ 的职责边界**：outbox 行负责「不丢」（原子性），RocketMQ 负责「快、重试宿主、并行保序」——timeout 注册的 outbox 扫描先例证明不丢不依赖 MQ，选 MQ 是复用推送链路 + 毫秒级投递。
- **已知待确认（实施票处理）**：撤回推送是否已走 outbox；扩散队列的 user_id 保序配置在现行 RocketMQ 拓扑下的落点。
