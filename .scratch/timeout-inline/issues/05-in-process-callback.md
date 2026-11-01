# 05 进程内回调分发（删 Dubbo 回调边）

Type: task
Status: resolved
Blocked by: 02

## Answer

**已落地（2026-10-17）。** 分发本体（`usecase.timeout.TimeoutTaskCallbackDispatcher`，票 04 已抽出）原地保留，本票删的是 Dubbo 边在业务侧的全部残体：

**删**：`bs-trigger/rpc/TimeoutTaskCallbackDubboService`（`@DubboService(group = "${spring.application.name}")` 的 Provider，批内扇出 + 双清单回报随它消失）；`bs-trigger/pom.xml` 的 `timeout-center-api` 依赖 + 根 pom 的 `dependencyManagement` 条目（至此 better-school 全模块零 `com.betterschool.timeout.api` 引用）。旧中心侧的 `TimeoutTaskCallbackReferences` / 中心 infrastructure 的 Port / api jar 本体**不在本票**——`backend/timeout-center/` 整目录随票 09 下线，票 07 的双跑窗口裁决前旧中心还得编译。

**测试面改线**（5 个类从 `@Autowired TimeoutTaskCallbackDubboService` 换成 `@Autowired TimeoutTaskCallbackDispatcher`，DTO 构造换域实体构造）：`TimeoutTaskCallbackIntegrationTest`（商品/跑腿两路支付超时 + 幂等 + 坏项隔离）、`ErrandLockTimeoutIntegrationTest`（复合 bizId + 轮次守卫）、`ErrandGrabAbuseCooldownIntegrationTest`、`ReviewTaskDecideIntegrationTest`、`ReviewTaskLeaseIntegrationTest`（租约轮次）。原「双清单回报」断言改为共享夹具 `integration/support/TimeoutCallbackFixture`（`dueTask` 造实体 + 逐项 catch 的批量驱动器，返回失败 key 集）——与扫描 case `TimeoutTaskScanCase.callback` 的逐项隔离同口径，五个类不再各抄一份；「空批次返回空双清单」用例随批量契约一起删（批概念回归扫描 case，探针桩已有覆盖）。

**验收**：
- 四个 taskType 各有集成测试跑到点动作 ✅（上面 5 个类；轮次守卫命中 0 行 = 幂等成功的用例在 `ErrandLockTimeoutIntegrationTest` 与 `ReviewTaskLeaseIntegrationTest`）。
- `grep -rE "ITimeoutTaskCallback\b|TimeoutTaskCallbackDubboService|ReferenceConfig"` 在 `better-school` + `marketing` 生产与测试代码零命中 ✅（grep 加了 `\b` 词边界：域端口 `ITimeoutTaskCallbackPort` 是**保留的进程内端口**，裸子串 grep 会误命中它——票 09 全仓反查时沿用词边界口径）。
- 单条回调抛异常、同批其余项照常结算 ✅——生产所在地是扫描 case 的批内逐项 catch，`TimeoutTaskScanIntegrationTest` 的「批内一项失败不拖垮同批其余项」（probe `failOnly`）钉住；测试驱动器的同口径隔离保住了原失败清单断言。
- 9 个相关集成测试类 46 用例全绿；全量 `mvn -o test -pl bs-app -am` 绿。

**文档**：arch doc §0 增补「进程内回调分发（票 05 已落地）」条目、§9 加过时横幅——`spring.application.name` 作为 group 来源与「应用改名静默失配」那条坑随 `callback_group` 概念整体消失（列在票 02 已删、group 概念随本票最后一块残体出清）。§1–§9 的整体改写仍留给票 09。

## What to build

把「中心 → 业务」这条 Dubbo 边**改成进程内方法调用**：

1. **删**：`ITimeoutTaskCallback` / `TimeoutTaskDTO` / `BatchCallbackResult` / `TimeoutTaskCallbackReferences`（动态 `ReferenceConfig`）/ `TimeoutTaskCallbackPort` / `TimeoutTaskCallbackDubboService`（`@DubboService(group = "${spring.application.name}")`）/ `timeout-center-api` jar 依赖 / Nacos 上的回调 provider 与 consumer 边。
2. **分发**：case 层按 `taskType` switch 调四个既有域动作（`ProductOrderCase.handlePayTimeout` / `ErrandOrderCase.handlePayTimeout` / `ErrandOrderCase.handleLockTimeout` / `IReviewService.handleLeaseTimeout`），**逐项隔离**（一项抛异常不拖垮同批其余项），并保留「未注册的 taskType 与坏 `bizId` 一律按失败回报」的口径（进重试/死信，不静默吞）。
3. **复合 `bizId` 解析**保留（`parseCompositeBizId`：`{orderId}:{lockSeq}`、`{taskId}:{claimSeq}` 拆不开即失败）。
4. **幂等责任不动**：仍由各业务状态机的条件 UPDATE 承担（未命中 = 幂等成功），**不要**新加去重表。

## 验收

- 四个 taskType 各有一条集成测试跑到点动作（含轮次守卫命中 0 行 = 幂等成功）。
- `grep -r "ITimeoutTaskCallback\|TimeoutTaskCallbackDubboService\|ReferenceConfig"` 在业务代码零命中。
- 单条回调抛异常时，同批其余项仍成功结算。

## 注意

删掉 Dubbo 边之后，`spring.application.name` 作为 `callback_group` 的来源、以及「应用改名会静默失配」那条坑（arch doc §9）一并消失——文档要跟着改。
