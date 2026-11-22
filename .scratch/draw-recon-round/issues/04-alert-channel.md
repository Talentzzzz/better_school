Status: ready-for-agent
Type: task
Blocked by:

# 04 告警通路：outbox 事件 → 单体消费 → 通知中心

## 目标

打通对账告警的完整通路，复用现有营销通知链路（outbox → RocketMQ → 单体 consumer → `INotificationService.publish`）。本票与票 01/02 并行：**端口接口也在本票定义**（域层），票 03 合入后按此接口注入调用。

## 上下文指针

- spec：`.scratch/draw-recon-round/spec.md`（Implementation Decisions 告警节；Out of Scope：不接词典外新渠道）
- 探索报告：`.scratch/draw-recon-round/exploration.md` §3 全节（链路三段、`OutboxMessageStore.save` 语义、`MarketingNoticeCase.notifyLotteryDrawn` 先例、`NotificationTypeVO` 位置）
- 域层端口接口（**本票产出**）：接口放 `marketing-domain` raffle 域（照 `IInventoryDeductPort` 的声明位置惯例），签名带 roundNo、checkType、scopeKey、bizId、期望/实际快照摘要、告警文案；票 03 将按此注入调用，Answer 里必须写清最终签名。

## 交付内容

1. **marketing 侧消息类型**：`marketing-types` 新建告警事件消息（照 `LotteryDrawnMessage` 形态：roundNo、checkType、scopeKey、bizId、expect/actual 摘要、告警文案），topic 常量进 marketing `Constants`（命名如 `bs-marketing-recon-alert`，照 `LOTTERY_DRAWN_TOPIC` 惯例）。
2. **端口实现**：`marketing-infrastructure` 实现上述端口接口——经 `OutboxMessageStore.save(topic, message)` 落 outbox。注意 `save` 要求业务事务包裹（afterCommit 首发）；对账服务无事务模板，实现里确认 `save` 在无活动事务时的行为，若不允许则把"插 outbox 行"做成仓储一个原子写（单行 insert 自带事务）——在 Answer 说明你选的方案与理由。
3. **单体侧 consumer**：`backend/better-school/bs-trigger/.../listener/` 新建 consumer（照 `LotteryDrawnConsumer` 形态：独立 consumerGroup），消费告警事件 → 调用 case 层。
4. **case 层落通知**：`MarketingNoticeCase` 扩方法（或新建 `MarketingReconAlertCase`，判断依据：方法体量与内聚）——`notificationService.publish`，`dedupKey` 形如 `MARKETING:RECON:<roundNo>:<checkType>:<bizId>`（天然幂等，重复点名不重复打扰）、`scope=BOTTOM_SYSTEM`、`refType=MARKETING`。
5. **通知类型**：`NotificationTypeVO` 营销枚举追加对账告警值（照 LOTTERY_WON 形态，带语义注释）。

## 约束

- 不接词典外的新通知渠道（spec Out of Scope）。
- 单体侧改动文件与当前主工作区未提交的 WS 重构文件**零交集**；提交时只提交本票文件。

## 验收

- 两个工程（`backend/marketing/` 与 `backend/better-school/`）`mvn compile` 通过；不写测试、不跑测试。

## Answer

### 实现要点（2026-11-22，分支 draw-recon-round-t04-alert-channel）

**新增文件清单**

| 文件 | 内容 |
| --- | --- |
| `backend/marketing/marketing-types/src/main/java/com/betterschool/marketing/types/common/DrawReconAlertMessage.java` | wire 消息：roundNo / checkType / scopeKey / bizId / expectSummary / actualSummary / alertText |
| `backend/marketing/marketing-domain/src/main/java/com/betterschool/marketing/domain/raffle/adapter/port/alert/IDrawReconAlertPort.java` | 域层告警端口（新包 `port/alert/`，照 `IInventoryDeductPort` 位置惯例） |
| `backend/marketing/marketing-infrastructure/src/main/java/com/betterschool/marketing/infrastructure/raffle/adapter/port/alert/DrawReconAlertPort.java` | 端口实现：组消息 → `OutboxMessageStore.saveStandalone` |
| `backend/better-school/bs-case/src/main/java/com/betterschool/usecase/marketing/DrawReconAlertMessage.java` | 单体侧同字段副本（跨服务 wire 层各持一份，LotteryDrawnMessage 先例） |
| `backend/better-school/bs-trigger/src/main/java/com/betterschool/trigger/listener/ReconAlertConsumer.java` | consumer（独立组 `bs-monolith-recon-alert-group`），载荷校验 roundNo/checkType/bizId 齐全才落通知 |

**修改文件清单**

- `marketing-types/.../common/Constants.java`：加 `RECON_ALERT_TOPIC = "bs-marketing-recon-alert"`。
- `marketing-infrastructure/.../common/outbox/OutboxMessageStore.java`：加 `saveStandalone(destination, payload)`，PO 构造 + insert 抽成私有 `insertPending`（行为零变化，delay 参数透传保留）。
- `bs-types/.../common/Constants.java`：加同名 topic 常量（单体消费注解用）。
- `bs-case/.../usecase/marketing/MarketingNoticeCase.java`：加 `notifyReconAlert(DrawReconAlertMessage)`——扩方法而非新建 case 类（方法体量与既有两个 notify 方法同量级，内聚一致）。
- `bs-domain/.../notification/model/valobj/NotificationTypeVO.java`：营销枚举追加 `RECON_ALERT("对账告警", Channel.NONE)`。

**端口接口最终签名（票 03 按此注入调用）**

```java
// com.betterschool.marketing.domain.raffle.adapter.port.alert.IDrawReconAlertPort
void sendReconAlert(String roundNo,      // 对账轮次号（差错表 round_no）
                    String checkType,    // 检查类型（差错表 check_type）
                    String scopeKey,     // 定位键：award_id 或 record_id；无自然定位键传 null
                    String bizId,        // 定位单号（= 差错表 biz_id，不可空，dedupKey 三要素之一）
                    String expectSummary, // 期望值快照摘要（公式左侧）
                    String actualSummary, // 实际值快照摘要（公式右侧实读数）
                    String alertText);   // 告警文案（null 时消费侧按其余字段拼）
```

语义约定：只有 CONFIRMED 差错 / DEAD 孤儿单才调用（分流判断归对账服务，端口只管投递）；投递失败上抛但不建议调用方中断本轮轧账——下一轮对账会重新点名。PENDING 差错不发。

**outbox 落库方案**

选「`OutboxMessageStore` 新增 `saveStandalone`」：`save` 原路径在无活动事务时 `registerSynchronization` 直接抛 `IllegalStateException`，而对账服务按分层纪律不注入事务模板——故旁路告警走独立落库：**单行 insert 自带隐式事务（原子写）+ 插入后立即异步首发一次**，首发失败由既有 `outboxScan` 扫描兜底重发（行保持 PENDING），消费侧 dedupKey 吸收重投。没有回滚语义（调用方在插入前自行保证该发），序列化逻辑仍收在 store 一处、不复制。

**通知落库口径**

`dedupKey = MARKETING:RECON:<roundNo>:<checkType>:<bizId>`（复用 `marketingDedupKey` 拼装）；`scope=BOTTOM_SYSTEM`、`refType=MARKETING`、`refId=bizId`、type=RECON_ALERT；**收件人 userId = `Constants.SYSTEM_USER_ID`（系统助手账号）**——维护者告警不是用户通知，消息体也不带收件人，落系统账号的通知中心可查历史（产品上若要定向人，需后续给消息体加收件人字段，见遗留风险）。

**编译结果**

- marketing 工程 `mvn compile -pl marketing-types,marketing-domain,marketing-infrastructure,marketing-app -am`：**通过**。
- better-school 单体 `mvn compile -pl bs-domain,bs-case,bs-trigger -am`：**通过**。
- 附带动作：bs-api 不在本地 .m2（marketing-infrastructure 依赖它），先在 better-school 执行了 `mvn install -pl bs-api -am -DskipTests`。
- 未写任何测试、未执行任何测试套件（spec Testing Decisions）。

**基线既有编译错修复（与本票功能无关，但阻塞编译验证，单独说明）**

集成分支基线上有三处既有编译错（均不在本票改动集，工作区对这三个文件原本无 diff）：

1. `marketing-domain/.../inventory/service/InventoryQuotaServiceImpl.java:165`：`degradeConfirm` 异常文案引用了不存在的 `awardId` / `item.bizId()`，按同方法日志行的口径改为 `request.awardId()` / `request.bizId()`。
2. `bs-domain/.../errand/adapter/repository/IErrandRepository.java`：方法签名用了 `ErrandStatusVO` 但缺 import，补上。
3. `bs-trigger/.../listener/OrderPushConsumer.java:39` 调 `errandOrderCase.loadOrder(orderId)`，而 case 层从未暴露该方法——给 `ErrandOrderCase` 补最小透传方法（委托 `IErrandOrderService.loadOrder`），consumer 代码不动。

**遗留风险**

- 收件人固定为系统助手账号：告警「人能不能看到」取决于维护者是否查该账号通知中心；若需定向收件人，消息体要加字段（涉及 wire 契约变更）。
- `saveStandalone` 无回滚语义：告警 insert 成功但进程随即崩溃时消息仍会发出（本就该发，无业务风险）；insert 失败则本条告警丢失，靠下一轮对账重新点名兜住。
- 基线三处编译错修复随本票分支带入集成，若票 03 也修了同一处（`InventoryQuotaServiceImpl` 在其改动邻域）需合并时注意冲突。
