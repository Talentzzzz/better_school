# 票 04 · 删除多人单与群聊，chat 收敛 1v1

Type: task
Status: resolved
Blocked by: （无）

## Question

多人单（跑腿拼团）与群聊能力整体下线，1v1 私信完整保留：

- 后端：bs-domain `errand` 域多人单逻辑（成团/补位/散团、每人报酬、所需人数）及 `errand_order_member` 相关代码；chat 域群聊能力：多成员会话（成员上限 2~500）、系统群（成团建群）、封盘、踢人、SYSTEM 系统消息——room/room_member 抽象保留但收敛为恒两行；1v1 的消息序号/时间线/已读水位/投递序号/增量补拉/发送幂等/撤回/互踢/连接票据/心跳全部不动。
- chat 强制撤回（管理端）保留（走消息撤回机制），消息举报链路保留。
- CONTEXT.md 词条修订：删除「多人单、每人报酬、成团、补位、散团、系统群、封盘、系统消息」；「会话」定义改为仅 1v1（两名成员）；「先付后审」仅跑腿订单（与票 03 协同）；「对家角色」「会话来源」词条复核。
- 验收：编译过、chat/errand 测试改绿；跑腿回到单人单同一链路。

## Answer

（2026-10-05 完成，全量测试绿）

**多人单整删（12 文件）**：
- bs-domain：`errand/model/entity/ErrandOrderMemberEntity`、valobj 五件（ErrandMemberStatusVO / ErrandMemberActionVO / ErrandMemberChangedMessage / ErrandGroupedMessage / ErrandLeaveResult）。
- bs-infrastructure：`dao/errand/po/ErrandOrderMemberPO`。
- bs-api：`ErrandOrderMemberDTO`。
- bs-trigger：ErrandGroupedConsumer、ErrandMemberChangedConsumer（bs-errand-grouped / bs-errand-member-changed 消费方）。
- 测试：6 个多人单集成测试（Publish/Join/Grouping/ExitBackfill/CompleteSettle/Evaluation）。
- `Constants.ERRAND_GROUPED_TOPIC` / `ERRAND_MEMBER_CHANGED_TOPIC` 两 topic 删（REFERRAL_GROUPED 是营销拉新，保留）。

**跑腿收敛单人单**：
- `ErrandOrderEntity`：requiredTakers / roomId / memberCount / members 字段与 isMultiTaker() 删，getTotalReward = reward；`create()` 签名去 requiredTakers。
- `IErrandOrderService` / `ErrandOrderServiceImpl`：joinTeam / leaveTeam / listInTeamUserIds / bindGroupRoom / startGroupedIfFull / scatterGroupingIfShort / inTeam 整删；grab/pickup/deliver 的 isMultiTaker 分支删（单人链路原样保留）；resolveEvaluationTarget 收敛为订单双方推导；handleOrderTimeout 散团段删。
- `IErrandRepository` / `ErrandRepository` / `IErrandDao` / `ErrandMapper.xml`：成员段（lockById/findMember/countInTeam/listInTeam*/insertMember/rejoinMember/leaveMember）、scatterGrouping、startGrouped、bindRoomId、pickupMultiById、deliverMultiById、saveErrandGroupedMessage、saveMemberChangedMessage 全删；selectMyTaken/countMyTaken 收敛为 taker_id 等值。
- `ErrandController` + `IErrandService`：`DELETE /orders/{id}/members/me`（leaveTeam）端点与 requiredTakers 透传删；ErrandOrderPublish/Response DTO 相应字段删（toUserId 保留，单人单由订单双方推导）。
- `ErrandOrderCase`：chatGroupService 依赖、handleErrandGrouped、handleErrandMemberChanged、notifyScattered、sealGroupIfBound 及全部封盘调用点删。

**chat 收敛恒 1v1（room/room_member 抽象保留）**：
- 整删：`IChatGroupService` / `ChatGroupServiceImpl`（createGroup/addMember/removeMember/sealGroup、chat.group.max-members）、`SystemEventVO`、`RoomRefTypeVO`、`RoomTypeVO`、`RoomStatusVO`。
- `MsgTypeVO` 收敛 TEXT/IMAGE/CARD 三类（SYSTEM 删，用户侧拒绝分支随之消失）；`RoomEntity` / `RoomSummaryVO` / RoomPO / RoomSummaryPO 的 type / name / refType / refId / status 列删，`RoomUserEntity` 的 leftAt / inRoom() 删（成员恒两行无移除语义）。
- `IChatRepository` / `ChatRepository` / `IChatDao` / `ChatMapper.xml`：selectRoomByRef、selectRoomForUpdate、markSealed、countCurrentMembers、markLeft、clearLeft 全删；room 读写列收敛。
- `ChatSendServiceImpl`：SEALED 预检 + 锁后重检两段守卫删（发送只走 findRoomById + 成员判定 + 载荷校验 + 取号扇出）；`ChatMessageFanoutService`：SYSTEM 预览分支与被移除成员过滤删。
- 1v1 全链路不动：消息序号/时间线/已读水位线/投递序号/增量补拉/发送幂等/撤回/互踢/连接票据/心跳；管理端强制撤回与消息举报链路保留。
- `ChatController` / ChatRoomSummaryResponseDTO / ChatRoomResponseDTO：type / name / refType / status 字段删。

**测试**：ChatGroupDomainIntegrationTest 整删；ChatPersistence（群 uk 段删）、ChatWs（三人群推送用例删，1v1 推送保留）、ChatSendPipeline（SYSTEM/SEALED/被移除段删）、ChatReadPath（SEALED 透出与被移除成员段删）、ChatRoomSource（refType 断言删）、AuthorProfile（insertErrand 去 required_takers）、ConsumeHandlerVirtualThread（消费者容器阈值 20→18，两 errand 消费者随 feature 下线）随改；CommunityApiTestBase 清表清单去 errand_order_member。

**SQL / 文档**：
- schema.sql：errand_order_member 表删；errand_order 的 required_takers / room_id 删、reward / deadline 注释收敛；room 的 type / name / ref_type / ref_id / uk_ref 删（status 保留但 SEALED 不再产生）；room_user 的 left_at 删；message 的 from_user_id 转 NOT NULL、msg_type 三类、content / client_msg_id 注释去 SYSTEM；wallet_transaction / errand_evaluation 注释去多人单口径。
- CONTEXT.md 七词条删（多人单、每人报酬、成团、补位、散团、系统群、封盘、系统消息——其中系统消息与消息类型词条合并改写）、会话词条改写为仅 1v1；会话来源 / 对家角色复核无需改（本就 1v1 口径）。
- backend docs：api/chat.md 删系统群领域层节与 SEALED/left_at 口径；db/chat.md room/room_user/message 重写；api/errand.md 删进团/退团/补位/封盘/按人结算节；db/errand.md 删 errand_order_member 表节；db/trade.md wallet uk 注释标历史件。

**保留不动（历史件，票 05 重建库时一并收敛，同票 03 口径）**：sql/migrations/016/017/018/019/020 及其 migration 测试（测试自建 legacy schema 执行真实文件，仍绿）；前端多人单 UI / 群聊入口随票 09。

**验收**：`mvn clean test` 全量绿（243 tests，含保留的 4 个 migration 测试）；主代码 grep（多人单/成团/补位/散团/系统群/封盘/requiredTakers/errand_order_member/joinTeam/leaveTeam/IChatGroupService/SystemEvent/RoomTypeVO/ERRAND_GROUPED 等）零命中——剩余命中仅营销 REFERRAL_GROUPED（独立 feature）、历史迁移件与票档。测试库已随新 schema 重建（test-env.sh reset-test-db）。
