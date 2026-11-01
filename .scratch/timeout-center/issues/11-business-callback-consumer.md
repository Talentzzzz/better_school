# 11: 业务侧回调消费（三关单点）

**What to build:** bs 单体实现 `ITimeoutTaskCallback`（`@DubboService`，group = 应用名），按 taskType 分发三路关单动作（spec §8，单 group 单实现）：商品订单 = 条件 UPDATE 取消 + 商品回在售 + 搜索同步消息；求购 = 取消求购；跑腿 = 取消跑腿单。回调处理必须幂等：状态机条件 UPDATE，未命中状态机（已终态）= 幂等成功不算失败，重复回调吞掉并返回成功（spec §6.4）。

**Blocked by:** 08（扫描 job 与批量回调闭环）

**Status:** resolved

- [x] 全链路验收 ×3：真实下单 → 到期 → 一个扫描周期 + 批处理时长内（精度口径 10~20s，spec §5.6）三路关单动作各自生效
- [x] 商品关单：订单取消 + 商品回在售 + 搜索同步消息，与现关单动作等价
- [x] 重复回调（同 taskType+bizId 重发）幂等吞掉并返回成功
- [x] 已支付/已取消订单收到回调：未命中状态机，零副作用，返回幂等成功

## Answer

2026-09-19 落地。**新增**：`bs-trigger` 的 `trigger/rpc/TimeoutTaskCallbackDubboService`
（`@DubboService(group = "${spring.application.name}")`，按 taskType 分发三路关单 + 逐项隔离 + 双清单全量回报）；
集成测试 `TimeoutTaskCallbackIntegrationTest`（5 用例）；冒烟脚本 `.scratch/timeout-center/smoke-ticket11.py`。
**改**：`TradePublishCase` 提出 `handleWantPayTimeout(Long)`（求购关单的 case 层入口，`scanWantPayTimeouts` 改为复用它）；
`bs-trigger/pom.xml` 引 `timeout-center-api`；`docs/architecture/timeout-center.md` 新增 §9 并把 §4 的 Provider 格填成具体类。

完成线：`mvn -o test` **352/352 绿**（票 10 的 347 + 本票 5）；全链路真机实跑通过（见文末）。

### 落地口径（都写进了代码注释与 architecture/timeout-center.md §9）

- **group 取 `${spring.application.name}`，不写字面量**：与注册侧写 outbox 的 `callback_group` 同源
  （`TimeoutRegisterOutboxStore` 的 `@Value`）。这条不是风格问题——写死字面量后应用一改名，中心那条动态
  ReferenceConfig 就找不到 provider，回调全部超时进重试/死信，而业务侧一条错都不报。
  动手前核过 Dubbo 会解析注解属性里的 `${}`（`AnnotationPropertyValuesAdapter` → `AnnotationUtils` →
  `resolvePlaceholders`），启动日志与 Nacos 注册面都实测确认解析成了 `group=better-school`（证据见文末）。
- **幂等零设施**：三路关单动作本来就是「读状态 → 条件 UPDATE」，未命中（已支付/已取消/不存在）直接返回、
  零副作用。所以本层**不建任何去重表**——重复回调走同一条路、照样回报成功（spec §6.4）。这是票面
  「未命中状态机 = 幂等成功」的字面实现，顺手也就没给未来的迁移 effort 留一份要维护的幂等键。
- **逐项隔离 + 全量回报**：批内每一项独立 try/catch，单项抛异常只进失败清单、不拖垮同批其他项；
  每一项都落进两个清单之一（中心把「两边都没回报」的项也按失败重投，只回报失败项会让成功项白重投一轮）。
- **未注册的 taskType 与坏 bizId 一律按失败回报**（不静默吞成成功）：前者意味着注册与消费的 `Constants`
  取值不一致，后者是注册方的编程错误。两条都重投到 16 次进死信——**死信列表正是 spec §6.1「全链路状态
  可查可改」要的落点**；静默成功反而会让「从没关成的单」看起来一切正常。这是本票的一处主动加法（spec 未规定
  此分支），已在票面与文档写明。
- **求购关单入口落在 `TradePublishCase`**：求购没有自己的 case 类，超时关单本来就住在这里
  （`scanWantPayTimeouts`），所以只把循环体提成 public 方法、让扫描与回调共用同一条路径，不外造类。
- **空批次的响应体用默认构造**（两个字段是可变 `ArrayList`）而不是 `List.of()`：响应要过 hessian2，
  不可变集合没有无参构造，序列化面不给自己埋雷。
- **日志 tag 用 `[TimeoutTaskCallback]` 而不是 `[超时中心回调]`**（code-review 采纳）：`[超时中心告警]`
  是中心与单体共用的告警标记（全仓 18 处，**全部是 warn/error**，靠它 grep 告警面），INFO 蹭同族前缀会把它糊掉。

### code-review 两轴采纳项

- **Spec 轴揪出「全链路验收无可入库证据」**：原脚本在断言之前就写 `smoke11-ids.json`，
  文件存在只证明「跑到了那一步」而不是「跑通了」。已改成**断言全过才落盘**，并补
  `smoke11-result.json` 记本轮观测（中心三态 + 业务侧状态 + 关单延迟）。
- Spec 轴还指出 `maxLatenessSeconds` 那行「精度口径」日志两头不讨好：既超出票面要求，又只采到成功的项、
  `now` 还冻结在批首（全失败批次打 0s）。**直接删掉**——中心自己就知道 `action_time` 与实际回调时刻，
  精度口径由中心侧度量更诚实。
- Standards 轴：本票日志前缀偏离仓库习惯 + 蹭了告警标记（同上，已改）。**未采纳**：测试夹具与
  `TimeoutRegisterOutboxIntegrationTest` 逐字重复——票 10 已明确拍板过「夹具留在各测试类内，只有基类共享」
  （同目录 ~10 个用例都是这个形状），抽公共基类会连带改票 10 的文件，属另一次决定。

### 已知残留 / 不在本票范围

- **spec §6.3 的「业务方批量条件 UPDATE」按逐项实现**：本票的硬要求是「与现关单动作等价」，
  商品关单是「订单取消 + 商品回在售 + 搜索同步」三写同事务、跨 trade 域内两个仓储，改成一条批量
  UPDATE 就得绕开现关单动作。代价是满批（200 条）串行可能超过中心的 2s 单次调用超时 → 中心把**整组**
  按失败重投。不是数据问题（幂等、零副作用），只是白做一轮；且失败项会被退避推后 30s 起，
  下一 tick 不会再捞同一批，不会卡死。三个支付超时的到点时刻天然分散，实际很难凑出满批。
- **中心侧扫描批大小（200）与「同步 < 1s」的张力是 spec 自带的**（§6.3 同时写了这两条），不在本票能解的范围内。
- **`tasks` 里若有 null 元素会整批失败**（key 的构造在 try 之外）：中心的批是从自己的表行构造的，
  不会发 null；要「修」只能把不可回报的项静默丢掉，反而违背全量回报。留作观察。
- **旧链路仍在**：`ProductPayTimeoutConsumer` / `productTimeoutScan` / `wantToBuyTimeoutScan` 等
  与新的回调路径**并行存在**（票 10 只切了注册方向）。二者都关同一批单、都幂等，共存无害；
  下线归迁移 effort（spec §10）。本票验收时它们也都还启用着（`xxl_job_info` 里 id 3/6/15 均开启）。

### 全链路实跑验收（本机，真实 Dubbo，非测试桩）

前置：8080 单体（本票新 jar）+ 8083 超时中心同跑，共用 127.0.0.1:8848 Nacos；`timeoutTaskScanJob` 启用态。
跑法：`python .scratch/timeout-center/smoke-ticket11.py`（`.scratch/timeout-center/smoke11-result.json` 是产物）。

结果（`smoke11-result.json`，一轮真实下单 → 到期 → 回调）：

- **注册面**：Nacos 服务列表里出现 `providers:com.betterschool.timeout.api.task.ITimeoutTaskCallback::better-school`
  ——`${spring.application.name}` 真解析成了 group（启动日志同时有
  `Registered dubbo service better-school/com.betterschool.timeout.api.task.ITimeoutTaskCallback … group=better-school`）。
- **中心侧**：三条任务注册为 `WAITING`、`callback_group=better-school`；把 `next_fire_time` 推到 60s 前
  （真实等 15min 太久）后，**2.4s 内**三条全部 `SUCCESS`（同一个中心进程跨 Dubbo 打回来，日志落在
  `:50051-thread-N`），远在 spec §5.6 的 10~20s 口径内。
- **业务侧**：`product_order=CANCELLED` + `product=ON_SALE` + 搜索同步消息关单前 2 → 关单后 3（回在售补一条）；
  `errand_order=CANCELLED`；`want_to_buy=CANCELLED`。
- 中心日志三条 `批量关单完成：成功 1 项、失败 0 项`，域服务三条 `待支付…超时取消`。

集成测试（5 用例）覆盖的是「业务方收到回调后做什么」：三路同批生效、商品三副作用齐全、重复回调幂等、
已支付/已取消零副作用、坏 taskType/坏 bizId 进失败清单且不拖垮同批。**跨进程路由（中心动态 ReferenceConfig
→ 本 Provider）测试栈盖不到**（测试栈没有中心进程），由上面的实跑覆盖。
