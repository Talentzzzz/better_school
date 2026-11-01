> **历史件**：本档是票 05 中途交接产物（effort 重划前的唯一存续记录），本 effort（project-rework）已随票 18 全部收口。内容仅考古用；踩坑清单 §5 仍被 map.md 引用（只在踩到环境问题时读）。

# 票 05 交接文档（主体库照 sql.md 重构）

> 交接时间：本轮对话末尾 · 目标：让下一个窗口不用重新考古就能接着干
> 票档：[`05-main-db-rework.md`](05-main-db-rework.md) · 地图：[`../map.md`](../map.md)

---

## 0. 一句话现状

**主体代码（main sources）已全部迁到新库表口径并编译通过；测试文件已由 4 个子代理机械迁移完成，但还没编译过、没跑过；且中间发现「审核轮次」口径被上一轮做坏了，我已经把主代码改回来，测试侧对应的改动还没回退。**
下一步就是：编译测试 → 回退轮次口径的测试改动 → 修剩余失败 → 跑全量 → 文档 → code-review → 提交。

---

## 1. 环境与命令（** wichtig：bash 坏了，别用 test-env.sh**）

| 项 | 值 |
| --- | --- |
| 仓库根 | `C:\Users\a1368\Desktop\better_school` |
| 分支 | `master`（单仓无远端） |
| Java / Maven | Java 21；`mvn`（`C:\Code\apache-maven-3.9.11\bin\mvn.cmd`），依赖已缓存，**加 `-o` 离线可用** |
| 常驻测试栈 | 已在跑：`bs-mysql`(13306) `bs-redis`(16379) `bs-minio`(19000) `bs-nacos-test`(18848) `bs-rocketmq-namesrv-test`(19876) `bs-es`(9200) |
| ⚠️ `backend/scripts/test-env.sh` | **跑不了**：`bash`/WSL 在这台机器上是坏的（`execvpe(/bin/bash) failed`）。用下面的 docker 直连替代 |

### 编译

```powershell
cd backend/better-school
mvn -q -o -DskipTests compile        # 主代码：当前 ✅ 无错
mvn -q -o -DskipTests test-compile   # 测试代码：⚠️ 还没跑完（下个窗口第一件事）
```

### 重建测试库（改过 schema 必须做，`CREATE TABLE IF NOT EXISTS` 不会改已存在的表）

```powershell
docker exec -i bs-mysql mysql -uroot -pbs123456 --default-character-set=utf8mb4 `
  -e "DROP DATABASE IF EXISTS better_school_test; CREATE DATABASE better_school_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
cd backend/better-school/sql
cmd /c "type init-test.sql schema.sql | docker exec -i bs-mysql mysql -uroot -pbs123456 --default-character-set=utf8mb4"
```

> ✅ **本步已做完并验证**：测试库已 29 张表、`room` 含 `user_min/user_max/source/initiator_id`、`sensitive_word` 种子 8 条。
> 开发库同理换成 `init-dev.sql` + `better_school`（本票要「本地开发库直接重建」，还没做）。
> `cmd /c type ... | docker exec -i` 是保 UTF-8 的关键；PowerShell 自己 pipe 中文会乱码。

### 跑测试

```powershell
cd backend/better-school
mvn -o test                                  # 全量
mvn -o -Dtest=PostDraftBoxIntegrationTest test   # 单个
```

### git 注意

`core.autocrlf=true`，所以 **`git status` 会虚报大量「已修改」**（其实内容没变）。
**权威改动清单只看 `git diff --name-only`**（当前 200 个文件）。

---

## 2. 已完成

### 2.1 DDL 与建库

- `sql/schema.sql` 已按 `sql.md` 第 1 节重写为 **29 张表**，逐表比对脚本：`.scratch/project-rework/cmp_schema.py`
  ```powershell
  $env:PYTHONIOENCODING='utf-8'; & C:\Code\Python\Python3.13.7\python.exe .scratch\project-rework\cmp_schema.py
  # → 27 identical，2 张仅注释措辞不同（post_like_count、room），可接受
  ```
- `sql/init-dev.sql` / `init-test.sql` 本来就只有 CREATE DATABASE + USE，无需改。
- `sql/migrations/*`（28 个）与全部 `*MigrationTest.java` + `MigrationTestBase.java` 已删（票面：不写迁移脚本）。

### 2.2 删表 / 改名 / 新增（全链落地）

- **删表**：`want_to_buy`、`errand_order_member`、`agent_review_log`、`errand_evaluation`、`user_daily_active`、`delay_outbox_message` —— PO/DAO/mapper/entity/仓储/域服务/job/consumer 全删（跑腿评价随本轮下线）。
- **改名**：`room_user→room_member`、`wallet_transaction→wallet_detail`、`user_credit_log→user_credit_detail`。
  - 类名也改了：`RoomUserPO→RoomMemberPO`、`RoomUserEntity→RoomMemberEntity`。
  - `IChatRepository` 方法改名：`insertRoomUsers→insertRoomMembers`、`findRoomUser→findRoomMember`、`listRoomUsers→listRoomMembers`、`countRoomUsers→countRoomMembers`（**测试已跟着改，别再改回来**）。
- **新增**：`post_like_count`、`post_comment_count`、`errand_order_event`、`product_order_event`。

### 2.3 计数窄表 + MQ 窗口聚合（票面验收项 1）

- `bs-infrastructure/.../adapter/repository/post/PostCountAggregator.java`（新）：内存窗口，**200ms 刷盘**，按帖把窗口内 ±1 合并成一条 UPDATE；`msgId` 环形去重（10 万容量 LRU）吸收 broker 重投；刷盘失败把增量并回窗口下轮重试；`@PreDestroy` 刷最后一把。
- `PostCountConsumer`（bs-trigger/listener）→ `IPostRepository.submitCountMessage` → 聚合器。
- 生产侧：`PostServiceImpl.likePost` = `INSERT post_like` + 同事务落 outbox 计数消息；`unlikePost` 发 −1；`postDetail` 发浏览量 +1。`PostCountMessage.of()` 每次生成 UUID 当 `msgId`（outbox 重发复用同一 payload ⇒ 同 msgId ⇒ 去重；比「post_like uk 当幂等键」更正确，因为 like→unlike→like 不该被去重掉）。
- 评论数：`post_comment_count` **同事务同步** upsert（`PostCommentMapper.upsertPostCommentCount`），不走 MQ。
- 评论点赞数仍在 `post_comment.like_count` 同步 ±1；商品浏览量维持原 Redis+MQ 路径（`sql.md` 口径未变）。
- 查询侧：`PostMapper.baseColumns` 用 `LEFT JOIN post_like_count / post_comment_count + COALESCE` 投影出 `likeCount/commentCount`，`post` 行不再存这两列。

### 2.4 订单事件流水（票面验收项 2）

- `errand_order_event` / `product_order_event` + PO + mapper `insertOrderEvent`。
- `ErrandRepository` / `ProductOrderRepository` 的**每一个**条件 UPDATE 成功分支都落一条事件（CREATED/PAID/TAKEN/PICKED_UP/DELIVERED/CONFIRMED/TIMEOUT/PAY_TIMEOUT/DISPUTED/ARBITRATED/REVIEW_APPROVED/REVIEW_REJECTED/AUTO_COMPLETED 等），`from_state` 为 null 表示创建。
- 顺手：`ErrandRepository.reviewRejectById` 现在把拒绝 `reason` 写进事件的 `reason` 列（原先该列恒 null）。

### 2.5 上一轮遗留缺陷（我已修，别再改回去）

1. `IProductDao` 缺 `bindBizId` 声明 → 补齐（这是最初的主代码编译错误）。
2. `ErrandRepository.save` 用 `orderPO.getErrandOrderId()`（插入前恒 null）当业务 ID → 改 `getId()`。**不改的话 `createOrder` 返回 null、支付超时注册到空 bizId。**
3. `PostCommentRepository.saveComment` 同型错误（`commentPO.getCommentId()` → `getId()`）。
4. `PostCountAggregator.submit` 把 `viewDelta` 加了**两次**；且 `synchronized(slot)` 与 `buffers.remove` 之间有丢增量竞态 → 改成 `buffers.compute` + `remove`（同键互斥）。
5. `ModerationMapper` / `PostMapper` 里 `content_review.target_id = p.id` → 改用业务列 `p.post_id` / `p.product_id`。

### 2.6 chat 模块整体迁移（**这块上一轮完全没做**）

`ChatMapper.xml`、`IChatDao`、5 个 PO、`ChatRepository` 全部迁到新口径：

- `room`：业务键 `room_id`（插入写 0 → `bindRoomBizId` 回写；读/改一律 `WHERE room_id = ?`）。
- `room_member`（原 room_user）+ `create_time/update_time`。
- `message`：新增业务键 `msg_id`（插入写 0 → `bindMsgBizId`）、`status→state`、`created_at→create_time`、补 `update_time`；`selectMessageById` 按 `msg_id` 查。
- `timeline`：`create_time/update_time`；补拉 JOIN 改成 `m.msg_id = t.msg_id`（原来错连 `m.id`）。
- `timeline.msg_id` / `message.room_id` 引用的都是**业务 ID**；`ChatRepository` 的 `toRoomEntity/toRoomSummaryVO` 读 `room_id`、`toMessageEntity` 读 `msg_id`。
- Room 的 `user_min/user_max/source/initiator_id/uk_direct_pair` **保留**（见 §4 决策 A）。

### 2.7 notification 模块迁移（**上一轮也没做**）

- `NotificationMapper.xml`：`created_at→create_time`、补 `update_time`、新增业务键 `notification_id`（插入 0 + `bindBizId`）；`notification_seq` / `notification_read` 的 INSERT 补 `create_time/update_time`。
- `NotificationPO`：新增 `notificationId`，`createdAt→createTime/updateTime`；`NotificationRepository.toPO/toEntity` 跟着改，`insertOne` 插入后回写业务 ID。

### 2.8 其他主代码改动

- **API DTO 去掉死字段 `condition`**：`ProductResponseDTO` / `ProductPublishRequestDTO` / `ProductDraftSaveRequestDTO`（`sql.md` 的 product 无该列，`ProductConditionVO` 上一轮已删，这个字段没人写也没人读）。
  ⚠️ **前端还在发/显示「成色」**（`front/src/types/api.ts`、`api/trade.ts`、`ProductPublishView.vue`、`ProductCard.vue`、`ProductDetailView.vue`）→ 归**票 09** 清理。前端不改也不会构建失败（Spring 默认忽略未知字段），只是卡片上「成色」标签会渲染成空。
- **审核轮次列恢复**：`post` / `product` 恢复 `review_round` + `review_entered_at`（PO/Entity/仓储/mapper 全链），`ModerationMapper` 的队列与详情改读 `p.review_round`，`PostMapper.selectReviewTimeoutPosts` / `ProductMapper.selectReviewTimeoutIds` 改回按 `review_entered_at` 扫。理由见 §4 决策 B。
- `CommunityApiTestBase` 已改：清表清单换成新表（含窄表/事件表，去掉已删表）；`commentStatusOf` → `state`+`comment_id`；`postCommentCountOf` → 读 `post_comment_count`；新增 `postLikeCountOf`。
- 我亲自改写的 3 个 job 测试：
  - `JobFanoutVirtualThreadIntegrationTest`：变体 B 原用已删的 `DailyCreditJob`，改成「搜索索引全量重建」（同样是游标拉批到空的形状）。
  - `UnboundedQueryCursorIntegrationTest`：删掉「昨日活跃 / 低分封禁」两个用例（对应仓储方法已随 capability 下线）；**注意此文件的夹具必须让业务 ID == 物理 ID**（插入后 `UPDATE t SET <biz> = id`，不能用 900000 号段），否则 `WHERE id > cursor` 分页会断。
  - `SearchIndexRebuildJobIntegrationTest`：夹具按上面同一口径重写。

### 2.9 测试文件的机械迁移（4 个子代理，已完成）

| 组 | 文件 | 结果 |
| --- | --- | --- |
| community | 12 个 | 10 改 2 无需改 |
| chat + timeout | 9 个 | 7 改，2 项被 schema 挡住（**已由本轮决策 A 解开**，现有改动可用） |
| trade | 6 个 | 6 改 |
| errand + user + file/web/consume | 11 个 | 9 改 2 无需改 |

迁移口径：`created_at/updated_at→create_time/update_time`、`status→state`（bs_user/post/post_comment/product/errand_order/product_order/message/outbox_message/timeout_register_outbox）、`outbox_message.topic→destination`、`timeout_register_outbox.task_type→type`、按业务列查（`post_id/comment_id/product_id/errand_order_id/product_order_id/user_id/msg_id/room_id`）、`condition` 从请求体与断言里删、直插夹具补 `create_time/update_time` 与业务 ID。

---

## 3. 未完成（按顺序做）

### 3.1 [先做] 编译测试 + 回退「轮次口径」的测试改动

**背景（重要）**：上一轮把 `post.review_round` / `product.review_round` 删了，改成「`1 + COUNT(content_review)` 推导」，并让测试也按这个推导口径写。**本轮我已把主代码改回「读真实列」，所以这些测试必须回退**，否则会挂。

要回退的文件与内容（原值请 `git show HEAD:<file>` 对照）：

| 文件 | 子代理改成了什么 | 要回退成什么 |
| --- | --- | --- |
| `community/PostDraftBoxIntegrationTest` | 新增 `postRoundOf()` = `1+COUNT(content_review)`；「撤回→重提轮次加一」断言从 2 改成 1；方法名/注释去掉「轮次加一」 | 直接 `SELECT review_round FROM post WHERE post_id = ?`，值回 2；`review_entered_at` 断言改回读该列（现在读的是 `update_time`） |
| `community/PostReviewFirstReviewIntegrationTest` | 同上 `postRoundOf()`；`review_entered_at`→`update_time` | 读 `review_round` / `review_entered_at` |
| `community/PostReviewTimeoutRemindIntegrationTest` | 4 处时间锚点改成 `UPDATE post SET update_time = ...`；`postRoundOf()`；用例 2「撤回重提进新一轮后可再次提醒」被改写成绕道 reject→rejectToDraft→重提才能到第 2 轮 | 锚点改回 `review_entered_at`；**用例 2 恢复原样：撤回→重提本身就进第 2 轮、能再次提醒**（这正是决策 B 要修的行为） |
| `trade/TradeRejectDraftAndEditIntegrationTest` | 新增 `productReviewRound()`；一处 `review_round==1` 改成 `==2` | 读 `product.review_round`，值回 1 |
| `trade/TradeReviewDecideIntegrationTest` | 新增 `productReviewRound()`；「撤回重提进第二轮」的值从 2 改成 1 | 读 `product.review_round`，值回 2 |
| `trade/TradeTakedownExpireRemindIntegrationTest` | `productReviewRound()`；24h 提醒改成回拨 `update_time` | 读 `product.review_round`；锚点回 `review_entered_at` |
| `trade/TradeFullChainIntegrationTest` | 24h 提醒改成回拨 `update_time` | 锚点回 `review_entered_at` |
| `job/JobFanoutVirtualThreadIntegrationTest`（我写的） | 待审帖用 `UPDATE post SET update_time = ...` 进扫描集 | 改成 `UPDATE post SET review_entered_at = DATE_SUB(NOW(), INTERVAL 25 HOUR) WHERE post_id = ?` |
| `errand/*` | ERRAND 轮次恒 1，无需动 | — |

`content_review.review_round` 自身的断言（`SELECT review_round FROM content_review ...`）本来就对，别动。

### 3.2 [然后] 跑起来并修剩余失败

```powershell
cd backend/better-school
mvn -q -o -DskipTests test-compile
mvn -o test
```

预计还会踩到的点：
- 还有测试残留旧列名/旧表名（子代理声称自查干净，但没编译过）。
- 直插夹具漏 `create_time/update_time`（NOT NULL 无默认，严格模式会报 `Field doesn't have a default value`）。
- `ConsumeHandlerVirtualThreadIntegrationTest` 里消费者容器数阈值被改成 **16**（15 生产 + 1 探针）。若实际不符，只需调这一个数。
- 通知表列名改过后，所有断言通知的用例（CommentLike / CommentReviewHumanized / PostReviewFirstReview / RejectReasonEnum / TimeoutRemind / NotificationReadModel）现在才真正跑得通。

### 3.3 [验收缺口 1] 「点赞/浏览计数走 MQ 聚合落窄表」没有测试覆盖

测试栈**没有 test broker**，RocketMQ consumer 不会被真实触发，所以 liked→`post_like_count` 这条链路没有端到端用例。
建议补一个：`@Autowired IPostRepository` 调 `submitCountMessage(PostCountMessage.of(postId, 1, 0))`，然后 `await`（≤1~2s）`SELECT like_count FROM post_like_count WHERE post_id = ?` == 1；再发 −1 断言回 0。可放在 `CommunityApiTestBase` 的子类里（比如新增 `PostCountAggregationIntegrationTest`）。`PostCountAggregator.flush()` 是包内可见，测试调不到，只能轮询。

### 3.4 [验收缺口 2] 「订单事件落表」没有测试覆盖

`errand_order_event` / `product_order_event` 写了但没有任何用例断言。
建议在 `ErrandFullChainIntegrationTest` / `ErrandPayBeforeReviewIntegrationTest` / `TradeFullChainIntegrationTest` 里补几条：如支付后 `SELECT event_type, from_state, to_state, actor_role FROM errand_order_event WHERE order_id = ? ORDER BY id`，断言 `PENDING_PAYMENT→PENDING_REVIEW` 那条；创建时 `from_state IS NULL`。

### 3.5 [然后] 文档

- `backend/better-school/docs/db/*.md`（README / user / community / community_comment / trade / errand / chat / moderation / outbox）：**全部还是旧口径**（`created_at`、`status`、`room_user`、`wallet_transaction`、`user_credit_log`、`like_count` 在 post 行、`review_round` 已删等），要按新 schema 重写涉及的表。
- `backend/better-school/docs/api/*.md`：至少 `trade.md` 要去掉 `condition`；`chat.md` 要去掉系统群/SEALED 残留并核对 `source/peerRole`。
- 根 `CONTEXT.md`：核 `审核轮次`（恢复「进入待审次数」后与新实现一致，无需改）、`会话来源`/`对家角色`（决策 A 保留，无需改）、`通知收件范围`；确认没有引用已删表。
- 判决：CLAUDE.md 要求「改了表/字段就同步 docs/db」。

### 3.6 [然后] code-review

用 `/code-review`，基线用 `9593eb8`（重构前收口提交）或 `HEAD`（即票 04 提交 `656f840`）。重点让它审：sql.md 两处回写是否自洽、计数分级的幂等口径、事件表是否有漏落的状态分支。

### 3.7 [最后] 提交 + 回写票档

- 提交信息照本仓惯例（中文、`票 05 resolved：...`，末尾带测试数）。
- 回写 `.scratch/project-rework/issues/05-main-db-rework.md` 的 `## Answer`，**必须写明 §4 的两处 sql.md 回写与理由**，以及 §3.3/§3.4 补的测试、跑腿评价下线的连带影响（前端归票 09）。
- 顺手决定 `.scratch/project-rework/*.py`（十几个 scratch 脚本，含本轮的 `cmp_schema.py`、`rename_room_member.py`）：要么入库当工具，要么删掉，别留脏。

---

## 4. ⚠️ 两处 sql.md 回写（本轮决策，必须知道 / 可以推翻）

`map.md` 规定「sql.md 是库表唯一事实源」，但也写明「发现对不齐之处在票内定并回写 sql.md」。本轮回写了两次：

### 决策 A：`room` 补回 `user_min` / `user_max` / `source` / `initiator_id` + `uk_direct_pair`

- **原因**：`sql.md` 的 room 只有 `room_id/last_*/state/时间戳`，但这 4 列撑着两条已定行为：
  1. **1v1 查重**（`uk_direct_pair`，并发建会话撞键幂等回既有）——去掉后需另设计防重；
  2. **会话来源 + 对家角色标签**（`CONTEXT.md` 的「会话来源」「对家角色」词条、市集消息页标签、`ChatRoomSourceIntegrationTest`）——没有列就没地方存。
- **已问过用户，用户只回了「什么意思」，随后要求写交接文档 → 没有明确答复。** 我选了「加法」（可逆、不删功能、改动最小）。
- **如果用户其实要严格照 sql.md**：要把这 4 列 + uk 从 `sql.md`/`schema.sql` 删掉，并删 `RoomSourceVO`/`RoomPeerRoleVO`、`ChatController.parseRoomSource`、`ChatQueryServiceImpl.peerRoleOf`、Room PO/Entity/VO 的 source/initiator 字段、`ChatRoomSourceIntegrationTest`、CONTEXT 两词条、前端标签；1v1 防重需要新方案（例如 `room_id` 用用户对派生值让 `uk_room_id` 兜住）。**下个窗口最好先跟用户确认一次。**

### 决策 B：`post` / `product` 补回 `review_round` + `review_entered_at`

- **原因（三条，缺一不可）**：
  1. `content_review.review_round` 列注释写的是「同一目标第 N 次**进入待审**」= 进入次数；而上一轮实现成 `1 + COUNT(裁决行数)` = **裁决次数**。两者在「某一轮没有裁决就结束」（撤回→改→重提）时不等价 ⇒ 轮次不 +1。
  2. 帖子 24h 待审提醒的 `dedup_key` 里嵌了轮次（`PostPublishCase`），轮次不 +1 ⇒ **撤回重提后再也不会提醒**（原先的行为，`PostPublishCase` 注释明写「重提后新一轮须可再次提醒」）。三个测试用例因此变得不可达，子代理只好把断言改成「不会再次提醒」或绕道走 reject 分支。
  3. `sql.md` 里 post.state 注释声称提醒走「`timeout_register_outbox` 注册 AUDIT_REMIND，出判决时撤销，不在本表存待审进入时间」——**这个机制从没实现**（`grep AUDIT_REMIND` 只命中这句注释），现实是**轮询扫描**，所以必须要一个进入待审时间。而拿 `update_time` 当锚点不可靠：浏览量聚合器会 `UPDATE post SET ..., update_time = NOW()`（`PostMapper.increaseViewCount`），待审期被作者看一眼就把 24h 重置了。
- 顺带改了两处注释：`sql.md` 的 `post.state`（去掉未实现的 AUDIT_REMIND 说法）、`content_review.review_round`（去掉「POST/COMMENT/ERRAND 恒为 1」这句陈旧话——旧 schema 里也有这句，但那时 post 的轮次明显会到 2）。
- 代码已全部按此改完并编译通过；**只有测试侧还没回退**（见 §3.1）。

---

## 5. 踩坑清单（下个窗口直接复用）

1. **`git status` 会虚报**（`core.autocrlf=true` 的 stat-cache 假阳性，例如 `ProductResponseDTO.java` 明明是 M 但 `git diff` 为空）。**只看 `git diff --name-only`。**
2. **`CREATE TABLE IF NOT EXISTS` 不改已存在的表** —— 改 schema 后必须 `DROP DATABASE` 再灌，否则跑在过期表上出「假绿」或奇怪的 `Unknown column`。
3. **MySQL 严格模式**：业务 ID 列与 `create_time/update_time` 都是 `NOT NULL` 且无默认值。应用侧是「插入写 0 → `UPDATE t SET <biz> = id WHERE id = ?`」（各仓储的 `bindBizId`）；**测试直插必须自己给业务 ID 与两个时间戳**，否则直接报错。
4. **夹具号段分两种场合**：
   - 一般用例：业务 ID 用 `900_000 + seq` 号段（远高于自增），保证不与应用行撞；
   - **游标分页用例**（`UnboundedQueryCursorIntegrationTest` / `SearchIndexRebuildJobIntegrationTest`）：必须让业务 ID == 物理 ID（插入 0 再 bind），否则 `WHERE id > cursor` 与返回的业务 ID 脱节，分页会断。
5. **测试栈没有 RocketMQ broker、ES 也指向不可达端口**：consumer 不会被真实触发（通知/计数落库不会因发消息而自动发生）；ES 用 `FakeSearchRepository`（`@Primary`）替换。
6. `mvn` 用 `-o` 离线跑，别随手去掉。
7. **mapper XML 不参与编译**，列名写错只有运行时才炸——改完 mapper 一定要跑对应用例。
8. `backend/CLAUDE.md` 的分层约定：mapper XML 只放 `bs-app/src/main/resources/mybatis/mapper/`；domain 不互相注入；跨域编排放 case。
9. 前端 `condition`（成色）已无后端支撑，属票 09 范围。

---

## 6. 关键文件位置

**新增主代码**
- `bs-infrastructure/.../adapter/repository/post/PostCountAggregator.java`（计数窗口聚合器）
- `bs-infrastructure/.../dao/errand/po/ErrandOrderEventPO.java`、`.../dao/productorder/po/ProductOrderEventPO.java`
- `bs-infrastructure/.../dao/chat/po/RoomMemberPO.java`、`bs-domain/.../chat/model/entity/RoomMemberEntity.java`（改名而来）
- `bs-infrastructure/.../dao/wallet/po/WalletDetailPO.java`、`bs-domain/.../wallet/model/entity/WalletDetailEntity.java`、`.../valobj/WalletDetailTypeVO.java`

**本轮我重点动过的 mapper**
`ChatMapper.xml`（整篇重写）、`NotificationMapper.xml`（整篇重写）、`PostMapper.xml`、`ProductMapper.xml`、`ModerationMapper.xml`、`ErrandMapper.xml`、`ProductOrderMapper.xml`、`PostCommentMapper.xml`

**本轮我重点动过的 Java**
`ChatRepository`、`IChatDao`、`INotificationDao`、`NotificationRepository`、`ChatRepository`、`PostRepository`、`ProductRepository`、`ErrandRepository`、`PostCommentRepository`、`IProductDao`、`PostPO/PostEntity`、`ProductPO/ProductEntity`、5 个 chat PO、`NotificationPO`、3 个 product API DTO

**全量清单**：`git diff --name-only`（200 项）+ `git status --short | Where-Object { $_ -match '^\?\?' }`（新增/未跟踪）。

---

## 7. 票面验收对照

| 票面验收 | 状态 |
| --- | --- |
| `schema.sql` 与 `sql.md` 逐表一致 | ✅（`cmp_schema.py`：27 identical，2 张仅注释措辞差异） |
| 点赞/浏览计数走 MQ 聚合落窄表 | ✅ 实现完成；⚠️ **无用例覆盖**（§3.3） |
| 订单事件落表 | ✅ 实现完成；⚠️ **无用例覆盖**（§3.4） |
| 相关测试改绿 | ❌ **待做**（§3.1 → §3.2） |
| 文档 / CONTEXT 同步 | ❌ 待做（§3.5） |
| 提交 + 回写 Answer | ❌ 待做（§3.7） |

**建议的下个窗口第一串命令**

```powershell
cd C:\Users\a1368\Desktop\better_school\backend\better-school
mvn -q -o -DskipTests test-compile 2>&1 | Select-String ERROR
```

（编译错误会直接列出还剩哪些测试没跟上；然后再按 §3.1 回退轮次口径。）
