# 03: 成团自动开始与系统群建立

**What to build:** 多人单最后一席被占的瞬间自动成团：订单自动开始（现有 TAKEN 语义的多人版，无手动确认）、执行 deadline 重算为成团时间 + time_limit_hours、同事务创建系统群（发布方 + 全部接单者，GROUP_CREATED 系统消息落群）；群出现在各成员消息列表，群内可正常收发；这是 ChatGroupService 的首个生产调用。

**Blocked by:** 02

**Status:** resolved

- [x] 最后一人加入事务内：成员落行 + 订单转开始态 + deadline 重算原子完成；竞态下不成团（人数未满）则保持组队态
- [x] 执行 deadline = 成团时间 + time_limit_hours；超时扫描按新 deadline 生效
- [x] 系统群创建：成员=发布方+全部在团接单者；GROUP_CREATED SYSTEM 消息占 seq 走正常链路
- [x] 成团后进团入口关闭（人数已满，新人加入被拒）
- [x] 前端：成团后详情页展示执行态与群入口；成员消息列表出现该群（群名按多人单展示）
- [x] 集成测试：成团原子性（同事务断言）、deadline 重算、群与 SYSTEM 消息落库、满员后加入被拒

## Comments

**建群走 outbox 异步（spec 两路径选一）**：票面「同事务创建系统群」与仓库铁律「跨域一致性 = 幂等 + 重试，禁止 case 层包多域事务」冲突，取 spec §Implementation Decisions 给出的「落建群 outbox」路径——成团事务内（成员落行 + `startGrouped` 条件 UPDATE PUBLISHED→TAKEN + deadline 重算）同事务落 `bs-errand-grouped` outbox，`ErrandGroupedConsumer` → case `handleErrandGrouped` 顺序编排 `chatGroupService.createGroup` + 回填 room_id。未选「直接调 ChatGroupService」（HTTP 路径下建群失败后客户端重试 grab 只会得到「已在该订单的组队中」，成团事件丢失、群永久建不出来）；outbox 路径 broker 自动重试最终建群。集成测试以消费者直驱 seam 断言（MQ 一跳由 outbox 骨架背书）。

**建群幂等锚点 = room uk_ref**：outbox 至少一次投递（afterCommit 异步 + XXL-Job 兜底扫描），消费端必须业务幂等——`room` 加 `ref_type/ref_id` + `UNIQUE uk_ref`，`createGroup` 撞 uk 返回 `selectRoomByRef` 既有群（claim 与资源创建是同一条 INSERT，天然原子）；`errand_order` 加 `room_id`（条件 UPDATE `room_id IS NULL` 不覆盖），既是详情群入口的 roomId 来源，也是票 04（执行期 removeMember/addMember）与票 05（sealGroup）的 roomId 来源。崩溃自愈：建群提交后、回填前崩溃 → 重投 → uk_ref 命中返回既有群 → 回填成功。迁移 `018_errand_group_room.sql`（两列存量 NULL 行为不变），已应用联调库。

**createGroup 签名扩展**：`createGroup(name, memberIds, RoomRefTypeVO refType, Long refId)`，ref 非空才参与 uk_ref；`MAX_NAME_LENGTH` 从 impl 上移到 `IChatGroupService` 接口（case 层截断群名需引用，避免 case 依赖 impl）。群名 = 订单描述截断 50 字（room.name VARCHAR(50) 上限，建群后不可改）。

**成团并发口径**：沿用票 02 的锁订单行串行——`joinTeam` 事务内成员落行后锁内重读 `countInTeam`，达标才 `startGroupedIfFull`；最后一席竞争恰一人成功、成团恰发生一次（outbox 恰一条，测试实证）。竞态未满保持 PUBLISHED，组队散团路径（票 02）不受影响。

**deadline 重算与超时扫描兼容**：`markTimeout`/`scatterGrouping` 均带 deadline 双校验，成团重算后支付时刻落库的旧超时延迟消息到点自动失效（`ErrandTimeoutConsumer` 直驱实证：TAKEN 不取消不退款）。多人单 TAKEN/PICKED_UP/DELIVERED 超时走通用取消路径但 `taken=false`（`handleOrderTimeout` 对 multi 判定）——取消 + 全额退 + 无人扣分（测试固定该占位行为）；**未做**：joiners 通知与 sealGroup（执行期退出/补位/终态封盘完整语义属票 04/05，届时按 spec 补齐）。

**前端**：详情页多人单 TAKEN 展示「已成团」+「组队已成团，任务开始执行」说明，行动区加「进入协作群」（`canEnterGroup = roomId && (isPublisher || inTeam)`，后端对非成员 403 兜底）；成员卡 hint 按状态切换；「（已满员，等待成团）」死分支删除（满员即成团不再出现该状态）。群名展示走 room.name（= 订单描述），消息列表零改动。

**code-review 后补充**：ChatGroupDomainIntegrationTest 的 11 处 `chatGroupService.createGroup(...)` 改走测试内 2 参便利 helper（`createGroup(name, ids)` → 4 参 null, null），调用点零噪音；ChatWsIntegrationTest 1 处直改 4 参。原票 02「满员超时不散团」测试语义被成团取代（满员即 TAKEN，不再有满员 PUBLISHED 过期态），删除并由票 03 测试覆盖替代路径。**code-review 采纳**：①`selectRoomByRef` 仓储签名收 `RoomRefTypeVO` 枚举（原 String 使调用点被迫 `.name()`，Primitive Obsession）；②群入口扩展到 PICKED_UP/DELIVERED 行动区（原仅 TAKEN 分支，取货后入口消失不符合票面「成团后详情页展示群入口」）；③建群用例补成员 `GET /api/chat/rooms` 列表断言（「消息列表出现该群」自动化固定，原先仅冒烟留档）。**留档未改**：①case 层 `groupName()` 截断 + impl 长度校验两处知晓 50 上限（截断下沉会改变 createGroup「超长即拒」语义，chat 域测试断言该拒绝，接受双处）；②`handleErrandGrouped` 的 TAKEN 守卫与并发取消存在窄竞态窗口（判定后建群前订单被取消 → 为已取消订单建出 ACTIVE 群）——窗口仅 MQ 消费延迟秒级 vs 用户恰好瞬间取消，票 05 落终态 sealGroup（散团/完成/取消均封盘）后此类群会被追溯封盘，接受；③`startGroupedIfFull` 落空抛异常连带回滚成员行（取消/散团竞态下进团整体失败让客户端重试）——票面「竞态下不成团」指人数未满场景，此处行为合理口径外留档；④`listInTeamUserIds`/`bindGroupRoom` 服务层纯转发仓储系 case→domain 编排惯例（case 不直注仓储），对齐既有先例。

**验收**：全量后端 282 绿（新增 `ErrandMultiTakerGroupingIntegrationTest` 8 例：成团原子性含 deadline 重算秒级精度容差断言、并发最后一席恰一成团、满员加入被拒、旧超时消息失效、未满不建群、建群成员/事件/扇出/回填、重复消费幂等、取消不建群、执行期超时占位行为；删除满员超时 1 例），前端 `vue-tsc` + `vite build` 绿。双端 UI 冒烟（Playwright 乙账号浏览器 + 甲/丙 REST）：发布多人单（每人 ¥20 × 2）→ 支付 → 乙丙进团 → 订单 19 TAKEN、`room_id=6` 回填、room ref=ERRAND/19 群名=描述、room_user 3 人、GROUP_CREATED 占 seq=1 → 乙详情页「已成团 / 2/2 人 / 进入协作群」→ 点入 `/chat/6` 群页（群名顶栏 + 成员入口）→ 群内发消息实时上屏 → 丙 REST 会话列表出现该群且 preview=乙消息。冒烟数据已删还原（订单/群/用户/流水/outbox），018 已留联调库。
