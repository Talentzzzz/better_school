---
Labels: wayfinder:prototype
Type: prototype
Status: resolved
Blocked by: 01, 05
---

# 票 02：表结构 DDL 原型

## Question

基于票 01 的时间线模型结论与票 05 的群聊产品规则，把 chat 域定稿模型落成可评审的 DDL 原型：

- room / room_user / 时间线 / 消息内容 / 消息记录表（按票 01 结论定去留）
- 撤回、会话级隐藏的状态建模（挂在哪张表哪个字段，终态机怎么走）
- 已读位（per-room read_seq？）、会话精确未读数的支撑结构（计数器还是 COUNT）
- 群成员角色字段、群人数上限 500 的约束落点
- 索引设计要对着票 01 论证过的核心读写路径来

➡️ 产出：`backend/sql/init.sql` 风格的 DDL 草稿（prototype 资产，链接自本票）+ 每张表的核心读写路径一句话说明，供用户反应后迭代定稿。

## Comments

- 2026-09-09：用户对三个开放点全部按推荐定夺（uk_direct_pair 可空列采纳、preview 维持 200、被移除成员列表隐藏详情可追溯），原型定稿；并入 `backend/sql/init.sql` 与建表落地归实施 effort。

## Answer

（2026-09-09，[DDL 原型](../ddl-prototype.sql)，每张表头部注释即核心读写路径一句话说明）

五张表定稿原型（+ content_report 注释扩值一条）：

| 表 | 角色（票 01 四表分工） |
|---|---|
| `room` | 会话本体：last_seq 事务内原子取号、name 群名（系统传入不可改，1v1 为 NULL）、last_msg_type/preview/at 列表冗余、status ACTIVE/SEALED 封盘软删 |
| `room_user` | 成员状态唯一可变行：last_read_seq 水位线、hidden_at 会话隐藏、left_at 移除留痕不删行 |
| `message` | 内容中心只写一份：msg_type 四类 + content 载荷 + CARD ref_type/ref_id 独立列 + 撤回三件套 status/recalled_at/recalled_by |
| `timeline` | 不可变瘦收件箱：user_id/room_id/seq/msg_id/delivery_seq，只插不改，uk(user_id, delivery_seq) 即补拉游标 |
| `user_seq` | per-user 投递序号计数器（票 04 更正）：扇出事务内 UPDATE seq=seq+N 读回取号，行锁到提交保证单调=提交序 |

### 对票面问题的回答

- **消息记录表**：不建（票 01 否决记档，原型中不再出现）。
- **已读位**：`room_user.last_read_seq` 会话级水位线，不是 per-message read_seq；未读数 = `room.last_seq − last_read_seq` O(1)，**无计数器无 COUNT**（seq 事务内原子分配无空洞）。
- **撤回/会话隐藏的状态建模**：撤回挂 message 行（status NORMAL/RECALLED + recalled_at + recalled_by，撤回是消息状态不是类型）；隐藏挂 room_user 行（hidden_at，新消息清空弹回）——都挂在「读路径本来就要碰的那一行」上，时间线保持不可变。
- **群角色字段**：**无**（票 05 系统群定向，无群主/管理员/禁言）；500 上限不落 DB 约束，系统侧建群/加人 API 对 room_user COUNT 校验、可配置。
- **1v1 唯一会话**：room 加可空列 `user_min`/`user_max` + `uk_direct_pair`（群两列为 NULL，MySQL 唯一索引允许多 NULL），同一 uk 覆盖「1v1 同人唯一、群不受限」——替代旧 `message_thread.uk_user_pair`，这是本票新决策。
- **群名**：room.name 仅 GROUP 非空（票 05 建群系统传入不可改）；1v1 无群名，列表展示对方昵称。
- **索引对着读写路径**：room_user.idx_user（会话列表驱动行）+ message.uk_room_seq（分页游标 + WS 判重键）+ timeline.uk_user_delivery（增量补拉谓词）三主索引，其余不设——timeline 是瘦行，多余索引直接乘进每天 ~800 万行的写放大；message.idx_ref（CARD 反查）刻意不建，当前无该读路径（票 06 只说「可索引」），真出现时补。

### 开放点（2026-09-09 用户已定夺，全部按推荐采纳）

1. `uk_direct_pair` 可空列（`user_min`/`user_max`）方案**采纳**（替代方案 1v1 复用 room_user 反查或独立配对表都更绕）。
2. last_msg_preview 维持 **VARCHAR(200)**（原文上限 500，列表预览截断够用）。
3. 被移除成员（left_at 非空）**不再出现在会话列表**，会话详情与历史消息仍可追溯（留痕跑腿纠纷语义不变）。
