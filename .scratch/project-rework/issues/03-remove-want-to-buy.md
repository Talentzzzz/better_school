# 票 03 · 删除求购（want-to-buy）

Type: task
Status: resolved
Blocked by: （无）

## Question

求购功能整体下线（后端先行，前端随票 09）：

- 后端：bs-domain `trade` 域 `wanttobuy` 子包（IWantToBuyService / WantToBuyServiceImpl）及 case/trigger/infrastructure/api 各层求购代码；审核类型 `ReviewTargetTypeVO.WANT_TO_BUY`；chat 会话来源 `RoomSourceVO.WANT_TO_BUY`（先付后审冻结预算的支付路径一并删）。
- 注意 WANT_TO_BUY 作为枚举值可能被持久化（历史数据/审核记录/会话来源）——本地开发库随票 05 重建，不迁移历史，直接删值即可；如代码里有按值反序列化的兼容分支一并清理。
- CONTEXT.md 词条修订：求购相关词条删除（先付后审词条改写为仅跑腿订单适用、会话来源词条去掉 WANT_TO_BUY、草稿箱/待审等词条中的求购分支删）。
- 验收：编译过、相关测试改绿、grep 无 want-to-buy 活引用（历史文档除外）。

## Answer

（2026-10-05 完成，全量测试绿）

**整删（19 文件）**：
- bs-domain：`trade/service/wanttobuy/`（IWantToBuyService、WantToBuyServiceImpl）、`IWantToBuyRepository`、`WantToBuyEntity`、valobj（WantToBuyPaidMessage / WantToBuyReviewResult / WantToBuyStatusVO）。
- bs-infrastructure：`dao/wanttobuy/`（IWantToBuyDao、WantToBuyPO）、`adapter/repository/wanttobuy/WantToBuyRepository`、`WantToBuyMapper.xml`。
- bs-api：WantToBuyPublish/DraftSave/Edit/Response 四个 DTO。
- bs-trigger：WantToBuyPaidConsumer（bs-want-paid 消费方）、WantToBuyTimeoutScanJob。
- 测试：WantToBuyFrontendFlowIntegrationTest、WantToBuyPayBeforeReviewIntegrationTest。

**枚举/常量直接删值（本地库随票 05 重建，无兼容分支需要清）**：
- `ReviewTargetTypeVO.WANT_TO_BUY`、`RoomSourceVO.WANT_TO_BUY`、`WalletTransactionTypeVO.WANT_FROZEN/WANT_UNFROZEN`、`NotificationScopeVO.MARKET_WANT`（求购入审通知的生产点已删）、`Constants.WANT_PAID_TOPIC` / `WANT_TO_BUY_PAY_TIMEOUT` / `TRADE_MAX_IMAGES`（求购专属，无其他使用方）。

**共享代码收口**：
- TradePublishCase：删求购编排（publish/edit/draft 三件套/pay/takedown/withdraw/refund/expire/超时关单+扫描/notifyWantPaidReview/求购审核提醒），`walletAccountService` 随之无引用移除；到期/提醒扫描收敛为仅商品。
- ReviewCase：approve/reject 的 WANT_TO_BUY 分支删。
- ProductController + ITradeService：/api/trade/want-to-buy* 全部端点与声明删。
- ChatQueryServiceImpl.peerRoleOf：WANT_TO_BUY 分支删；ChatController 注释改「市集两域」。
- ModerationMapper.xml：审核队列/计数/详情/轮次四处 `targetType == 'WANT_TO_BUY'` 分支删。
- TimeoutTaskCallbackDubboService：WANT_TO_BUY_PAY_TIMEOUT 回调分支删（三路关单→两路）。
- 超时注册发送器 `TimeoutRegisterOutboxSender.cancelAsync`（求购支付后撤任务专用）现无调用方——**保留**：它是超时中心契约能力（spec §8 取消点、假端口支持），收敛归票 08 超时对齐。

**测试**：6 个混合用例（FullChain / ReviewDecide / RejectDraftAndEdit / TakedownExpireRemind / PublishPendingReview / DraftBox）删求购段保留商品/跑腿/帖子/评论段；ChatRoomSource 测删求购入口段；TimeoutRegisterOutbox/TimeoutTaskCallback 删求购段（三接入点→两接入点；「同键复位」「取消重试」两用例为求购专属路径，整用例删）；CommunityApiTestBase 清表清单去 want_to_buy。

**SQL / 文档**：
- schema.sql：want_to_buy 表删；room.source / notification.scope / wallet_transaction.type / content_review.target_type 注释同步。
- xxl-job-tasks.sql：wantToBuyTimeoutScan 任务 seed 删。
- CONTEXT.md 十词条修订（会话来源、通知收件范围、先付后审→仅跑腿、同步初筛、先审后售、编辑重审、待审、草稿箱、审核轮次、审核未通过（闲置））。
- backend docs：api/trade.md 删求购接口节；db/trade.md 删 want_to_buy 表节；api+db 的 chat.md / moderation.md / outbox.md 口径同步。

**保留不动（历史件，票 05 重建库时一并收敛）**：sql/migrations/007/008/010/011/026/027 及其 migration 测试（TradePublishPendingReview/TradeExpireRemind/ReviewRound/NotificationRead MigrationTest）——脚本含 want_to_buy 语句且测试须建该表才能执行真实迁移文件；前端随票 09。

**验收**：`mvn clean test` 全量绿；主代码 grep（wanttobuy/want-to-buy/want_to_buy/WANT_TO_BUY/WANT_FROZEN/MARKET_WANT/求购）零命中——剩余命中仅 .scratch 历史票档、sql/migrations + migration 测试（历史件）、sql.md line 12（已明示不收录）、前端（票 09）。
