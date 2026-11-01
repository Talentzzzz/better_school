---
Labels: wayfinder:task
Type: task
Status: resolved
Blocked by:
---

# chat 落地票 01：DDL 定稿并入 init.sql + 持久化层与领域模型重建

## Scope

依据 spec §1（[DDL 原型](../../chat-refactor/ddl-prototype.sql)）重建 chat 域存储层。

1. `backend/sql/init.sql`：落五张表 `room`/`room_user`/`message`/`timeline`/`user_seq`（含 spec §1 修订——`message.client_msg_id` 列 + `uk_client_msg(room_id, client_msg_id)`）；删除 `message_thread` 旧表定义；`message` 旧表定义删除重写；`content_report.target_type` 注释扩值 `MESSAGE`（无列变更）。
2. bs-infrastructure：五表 PO + DAO（MyBatis mapper）+ repository adapter 实现 `IChatRepository`（按新模型重新定义接口）；`content_report` 相关不动。
3. bs-domain：chat 领域 entity/valobj 重建（room、成员状态、消息四类型、时间线行）；删除 `MessageThreadEntity` 等旧模型。通知域（notice/NotificationEntity）不动。
4. 旧代码引用（`ThreadServiceImpl`/`ChatRepository` 等）在本票只做模型层替换，service 用例层重写归票 02/03——本票以编译通过 + 造数集成测试（建 room/扇出/取号）为完成线。

## 验收要点

- init.sql 可从空库一键建表；`uk_direct_pair` 对 NULL 群行不生效（可写测试验证 1v1 唯一、群不受限）。
- user_seq 计数器扇出事务内 `UPDATE seq=seq+N` 读回取号；timeline `uk(user_id, delivery_seq)`。
- 集成测试：发一条消息落 room/room_user/message/timeline/user_seq 五处状态正确。

## Answer

2026-09-10 落地（commit fcb6053）：

1. **init.sql**：五表按 DDL 原型落定，含票 07 修订（`message.client_msg_id VARCHAR(36)` + `uk_client_msg(room_id, client_msg_id)`，SYSTEM 为 NULL 不受约束）；`message_thread` 删除、`message` 重定义；`content_report.target_type` 注释扩 `MESSAGE`。
2. **bs-domain**：`RoomEntity`/`RoomUserEntity`/`MessageEntity`/`TimelineEntity` + 六 valobj（RoomType/RoomStatus/MsgType/MsgStatus/CardRefType/RecallBy）；`IChatRepository` 按新模型重定义为持久化原语集（取号 `increaseLastSeq`/`increaseUserSeq`、成员状态、内容中心、时间线扇出、`findMessageByClientMsgId` 幂等回查），事务边界由调用方圈定。删 `MessageThreadEntity`/旧 `MessageEntity`/`ThreadRefTypeVO`/`IThreadService`/`ThreadServiceImpl`。
3. **bs-infrastructure**：五 PO + `IChatDao` + `ChatMapper.xml` + `ChatRepository`。要点：`updateLastReadSeq` SQL 带 `last_read_seq < 目标` 守卫（只进不退）；`clearHidden` 限 `left_at IS NULL`（被移除成员不参与扇出不弹回）；`user_seq` 取号用 `INSERT .. ON DUPLICATE KEY UPDATE seq=seq+N`（行不存在按 N 建行）。
4. **旧引用下线**：`ChatController`/`MessageSendCase`/`IChatService` + 4 个 thread DTO 删除（零审核，REST 十一端点归票 02/03）；测试基类 TRUNCATE 清单补五表。
5. **完成线验证**：`ChatPersistenceIntegrationTest` 4 用例（uk_direct_pair 1v1 唯一/群 NULL 不受限、发一条消息五表状态正确、user_seq 累加/批量取号、水位线+隐藏弹回语义）；全量 211/211 绿；docs/db/chat.md 新建。

给后续票的备忘：发送链路（票 02）撞 `uk_client_msg` 时用 `findMessageByClientMsgId` 回查；取号与消息/时间线写入必须同事务同连接（接口 javadoc 已约定）；**本地联调库需重置**（旧 `message` 表与新定义冲突，spec §0 定死不迁移）。

## Comments
