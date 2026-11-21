# 02: 推送决策上收 case + 消费者瘦身（trigger 不再摸仓储）

**What to build:** 新增 chat 推送 case 服务，承接「按会话成员扇出、MSG/READ 排除触发者、RECALL 推全员、按事件类型组装帧（信封与 payload 现口径）」的全部决策逻辑，经 chat 仓储解析成员后逐成员调 `IWsPushPort.deliver`；MSG/READ/RECALL 三个推送消费者瘦身为反序列化 + 调 case（薄到没有可测的业务分支）；`ChatWsPushService` 删除，trigger 从此零仓储依赖；`NotifyPushConsumer`/`OrderPushConsumer` 按同一模式归位（各自扇出决策随 case），保持推送类消费者吞错不重试的两派语义与 notify 的 refType/refId null→"" 组帧归一化。既有 chat WS 集成测试族与 notify/order 集成测试零改动语义、只随装配调整。

**Blocked by:** 01 (delivery-machinery-to-infra)

**Status:** resolved（编译与单测已验证，行为等价待回归窗口）

- [x] case 新增 chat 推送 case 服务：按事件类型组装 MSG/READ/RECALL 帧信封与 payload（现口径不变）、经 chat 仓储解析会话成员、MSG/READ 排除触发者、RECALL 推全员、逐成员调 `IWsPushPort.deliver`；对标既有 case 层约定（`usecase.<域>.XxxCase`、跨域编排不做事务）
- [x] MSG/READ/RECALL 三个消费者改为反序列化 + 调 case，`ChatWsPushService` 删除，trigger 全层不再出现任何 chat 仓储依赖
- [x] `NotifyPushConsumer`/`OrderPushConsumer` 同规则归位：扇出决策收进对应 case，消费者只做反序列化 + 调 case；吞错不重试语义与 refType/refId null→"" 归一化保留
- [x] 集成测试装配随动：ChatWsIntegrationTest / NotifyChannelIntegrationTest / ErrandOrderPushIntegrationTest 断言零改动，仅调整直调消费者 Bean 的装配路径
- [x] 交付闸门（spec 拍板：不起环境）：better-school 全模块 mvn compile + test-compile 全绿 + 不依赖 Docker 的纯单测全绿；票面标注「编译与单测已验证，行为等价待回归窗口」

**落地注记（实现取舍，回归窗口重点关注）：**

- **落位与命名**：chat 推送 case 落 `bs-case` 的 `com.betterschool.usecase.chat.ChatWsPushCase`（原 `ChatWsPushService` 三方法 `pushMessage/pushRead/pushRecall` 原签名原口径整体位移，git 识别为 82% rename）；notify 组帧落 `usecase.notification.NotifyPushCase.push(NotifyPushMessage)`（refType/refId null→"" 归一化随帧组装一起上收，语义未动）；order 扇出落既有 `ErrandOrderCase.pushStatus(ErrandOrderPushEvent)`（对标该类既有消费者编排方法 `notifyPaid` 的先例，未开新 case 类）。
- **ErrandOrderCase.loadOrder 公开方法随之退役**：它是票 01 为 OrderPushConsumer 恢复的公开只读查询，本票后消费者不再直调（pushStatus 内部走 `IErrandOrderService.loadOrder`），全仓无调用方即删，避免死 API。回归窗口如有外部代码暗依赖需留意（grep 确认无测试引用）。
- **消费者语义分派未动**：五个推送消费者（chat MSG/READ/RECALL、notify NOTIF、order ORDER）全部保持 try/catch 吞错不重试 + 错误日志带 payload；落库类消费者（NotifyEvent/NotifyChat/Broadcast）不受影响。组帧 `ts=System.currentTimeMillis()` 口径、帧信封字段顺序与原实现逐字段一致。
- **集成测试零改动**：ChatWsIntegrationTest 直调三个消费者 Bean（Bean 类型未变、行为等价），NotifyChannelIntegrationTest 只直调 NotifyChatConsumer，ErrandOrderPushIntegrationTest 只断言 outbox 写侧——三个测试文件本次一行未改，纯位移由既有断言背书。
- **闸门实跑结果**：全模块 `mvn -q compile`/`test-compile` 绿；bs-app 纯单测（CountMergeConfigTest、TimeoutTaskScanServiceTest）绿；bs-infrastructure 纯单测 47 跑 5 失败，全部落在 merge 引擎测试且 bs-infrastructure 本票零改动（不依赖 bs-case/bs-trigger，失败按定义为基线既有，待合并器修复票）。注意基线失败集与票 01 注记的 4 个清单略有出入：实测失败为 CountMergerTest.metricsExposeMergeRatio / batchNeverExceedsMaxBatch / rejectsWhenFullWithoutTouchingQueue、CountMergeWriterTest.bufferFullDegradesToSameFlushMethodWithSingleItem、ViewMergeWriterTest.bufferFullDegradesToSingleUpdateOnCallerThread（acceptedItemsSurviveRejection 本次通过）——修复票应以实测为准。注册表两单测（7+4）与 CompositeTxExecutorTest 绿。
- trigger 层 chat 仓储依赖清零（grep `IChatRepository|adapter.repository` 仅剩 ChatController 的 IUserRepository 与 NotifyEventConsumer 的 INotificationRepository，均非 chat 仓储且不在本票范围）；PONG/互踢/判死链路（票 01 产物）未触碰。
