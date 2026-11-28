# 01: 缝① 线契约 ID 定型 + api 层改字符串

**What to build:** 把前端唯一的业务 ID 定型处 `front/src/types/api.ts` 里所有业务 ID 从 `number` 翻成 `string`，并把 `front/src/api/` 下的业务 ID 形参与承载业务 ID 的返回类型同步。数值语义字段一个都不许动。这是全仓的钥匙票：其余票都在它之后并读它的产物。

**Blocked by:** 无

**Status:** ready-for-agent

## 判据（唯一一条，别逐字段讨论）

后端响应 DTO 字段是 `String` → 前端 `string`；是 `Long` / `int` / `Integer` → 前端 `number`。
已核对过的后端 DTO：`CreditLogResponseDTO`、`NotificationResponseDTO`、`ChatMessageResponseDTO`、`UserPrizeResponseDTO`、`LotteryPrizeResponseDTO`、`ProductOrderResponseDTO`、`PostSummaryResponseDTO`、`RedeemDrawCountResponseDTO`；controller 签名见 `backend/better-school/bs-trigger/src/main/java/com/betterschool/trigger/http/*.java`。以 spec 的 `Implementation Decisions → 缝①` 为准。

## 1. `front/src/types/api.ts`

翻成 `string`（可空字段保留 `| null`）：

| 类型 | 字段 |
| --- | --- |
| `UserSummary` | `userId` |
| `UserInfo` | `userId` |
| `PublicUserProfile` | `userId` |
| `AuthorProfileComment` | `commentId`、`postId` |
| `PostSummary` | `postId`、`userId` |
| `ProductOrder` | `orderId`、`productId`、`buyerId`、`sellerId` |
| `PostDetail` | `postId`、`userId` |
| `PostComment` | `commentId`、`postId`、`userId` |
| `CommentReply` | `commentId`、`postId`、`parentId`、`userId`、`replyToUserId` |
| `Product` | `productId`、`userId` |
| `CreditLog` | `refId`（`string \| null`，`id` 保持 `number`——物理自增流水） |
| `ChatRoomSummary` | `roomId`、`peerUserId`（`string \| null`） |
| `ChatRoom` | `roomId` |
| `ChatMessage` | `roomId`、`messageId`、`fromUserId`（`string \| null`）、`refId`（`string \| null`） |
| `RoomMember` | `userId` |
| `Notification` | `id`、`senderId`（`string \| null`）、`refId`（`string \| null`） |

已经是 `string`、只需确认没被顺手改回 `number`：`PostPublishResult.postId`、`PaymentOrder.payNo`、`PaymentOrder.bizOrderId`。

保持 `number`（数值语义，逐个复核，别顺手改）：`PageResult.page` / `pageSize` / `total`，`UserInfo.creditScore`，`PublicUserProfile.receivedLikeCount`，`AuthorProfileComment.likeCount`，`PostSummary.likeCount` / `viewCount`，`ProductOrder.price`，`PostDetail.likeCount` / `commentCount` / `viewCount`，`PostPublishResult.pointsRebate` / `drawCountRebate`，`PostComment.likeCount` / `replyCount`，`Product.price` / `viewCount` / `favoriteCount`，`PaymentOrder.amount`，`CreditInfo.creditScore`，`CreditLog.id` / `change`，`ChatRoomSummary.unread`，`ChatMessage.seq` / `deliverySeq`，`ChatTimelinePage.nextCursor`，`RoomMember.lastReadSeq`，`RoomMembers.memberCount`，`Notification.seq`。

顺手把 `ProductOrder` 与后端 `ProductOrderResponseDTO` 的字段名对齐**只做 id 相关**，后端多出的 `quantity` 不在本票范围（不新增字段，避免扩大 UI 面）。

## 2. `front/src/api/*.ts`（41 处业务 ID 形参 + 相关返回类型）

`front/src/api/community.ts`：`listUserPosts(userId: string)`、`listUserLikedPosts(userId)`、`listUserComments(userId)`、`updatePostDraft(postId)`、`submitPostDraft(postId)`、`getPost(postId)`、`deletePost(postId)`、`likePost(postId)`、`unlikePost(postId)`、`likeComment(commentId)`、`unlikeComment(commentId)`、`listComments(postId)`、`createComment(postId)`、`listReplies(commentId)`、`createReply(commentId)`、`deleteComment(commentId)`。

`front/src/api/idle.ts`：`createProduct` 返回 `httpPost<string>`（后端 `Response<String>`，当前错写成 `number`）；`getProduct(productId)`、`listUserProducts(userId)`、`deleteProduct(productId)`、`markSold(productId)`、`relistProduct(productId)`、`favoriteProduct(productId)`、`unfavoriteProduct(productId)`、`createProductOrder(productId)` 返回 `httpPost<string>`、`getProductOrder(orderId)`、`markDeliveredProduct(orderId)`、`confirmProductOrder(orderId)`、`cancelProductOrder(orderId)`。

`front/src/api/chat.ts`：`createDirectRoom(targetUserId: string)`、`listMessages(roomId: string, params: { beforeSeq?: number; limit?: number })`（`beforeSeq` 是消息序号，保持 `number`）、`SendMessageBody.roomId: string`、`SendMessageBody.refId?: string`、`markRead(roomId: string)`、`recallMessage(roomId: string, seq: number)`（`seq` 保持 `number`）、`hideRoom(roomId: string)`、`listMembers(roomId: string)`。`markRead` 的返回 `httpPost<number>` **保持 `number`**（后端 `Response<Long>`，返回的是已读水位线）。

`front/src/api/marketing.ts`：`LotteryPrize.prizeId: string`、`DrawResult.recordId: string`、`DrawResult.prizeId: string | null`、`UserPrize.id: string`、`UserPrize.prizeId: string`、`confirmReceived(id: string)` 返回 `{ id: string; status: UserPrizeStatus }`、`exchangeDrawCount` 返回 `{ redeemOrderId: string; drawCount: number }`（`drawCount` 保持 `number`）。`DrawChances` / `LotteryActivity` / `getPointsBalance` 里的数值字段不动。本票**不要**动 `PRIZE_TINTS` 与其使用方式——票 07 在同文件加编号取色辅助。

`front/src/api/user.ts`：`getPublicProfile(userId: string)`。
`front/src/api/search.ts`、`api/credit.ts`、`api/pay.ts`、`api/auth.ts`、`api/files.ts`、`api/http.ts`、`api/ws.ts`：只依赖类型，逐文件确认没有残留的 ID 形参（`api/search.ts` 只有 `keyword/page/pageSize`）。

## 3. 不碰

`front/src/router`、`front/src/stores`、`front/src/views`、`front/src/components`、`front/src/utils`、`front/src/composables`（票 02–07 各自的文件）。不动后端、`docs/`、`CONTEXT.md`。

## 验收（静态检索自查，**不跑构建 / 类型检查 / 测试**，spec 交付口径）

- [ ] `front/src/types/api.ts` 中 `Id\s*[:?]\s*number` 命中 0（`number` 只出现在上表「保持 number」清单）。
- [ ] `front/src/api/` 中 `Id\s*[:?]\s*number` 命中 0，`httpPost<number>` 只剩 `markRead` 一处（且已确认是水位线）。
- [ ] 上表「保持 number」清单逐项人工对了一遍，一个没错改。
- [ ] `git diff --stat` 只包含本票文件。
