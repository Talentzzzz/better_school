# 12: 扫描结算批量化（每批每桶一条 UPDATE）

**What to build:** 扫描 tick 的**结算**（`TimeoutTaskScanService.settle`）从「逐行一条条件 UPDATE」改成
「每批按结算结果分桶、每桶一条批量条件 UPDATE」：成功桶置 SUCCESS、重试桶置 RETRYING 并按各自退避
推后 `next_fire_time`、死信桶置 FAILED。**契约与并行模型不动**——业务方的回报仍然是回调 RPC 的返回值
（`BatchCallbackResult` 双清单），中心不新增回报接口、不要求业务方回打（票 11 的形态保留）。

**Blocked by:** 无（票 07–11 全 resolved）

**Status:** resolved

- [x] 结算写库次数与「批内结果种类数」成正比，不再与批大小成正比（满批 200 条从最多 600 条 UPDATE 降到 ≤ 3 条）
- [x] 行级语义不丢：每条批量 UPDATE 仍是**条件 UPDATE**，只打未终态行（并发取消/死信重发的行不被翻转）
- [x] 重试桶各行保留各自的退避时刻（`retry_count` 不同的行推后到不同 `next_fire_time`）
- [x] 走 hint 强制路由，只落本槽位那一张分表（不因多值条件退化成广播，也不被路由校验拒掉）
- [x] 集成测试覆盖：批内混合结果（成功 + 重试 + 死信）、到点行被取消后不被批量 UPDATE 翻转、退避值各行独立
- [x] 文档同步：`timeout-center/docs/api/callback.md`、`docs/architecture/timeout-center.md` §6

## Question

现状（票 08/11 落地）的结算是在槽位线程上**逐行**跑条件 UPDATE：满批 200 条最多三次循环 × 200 = 600 条
SQL，全部串行在同一条连接上。批内已经按 `callback_group` 分组并行了网络调用、业务侧也已用虚拟线程扇出，
**剩下的瓶颈就是结算的往返次数**。

为什么可以批量化而不受分库分表影响：扫描是**按槽位**进行的，一个批本来就在同一张物理分表上
（`selectDueBatch` 前仓储层已把槽位塞进 `HintManager`），所以「一条 UPDATE 覆盖整批」天然是单表语句
——不是「因为一台机器负责一张表」，而是「因为捞批的粒度就是一张表」。

## Answer

2026-10-03 落地。改的是**结算的写法**，契约、并行模型、幂等口径全不动（用户拍板走 A：回报继续走回调
RPC 的返回值，不新增「业务方回打中心」的回报边）。

### 落地口径

- **域服务**（`TimeoutTaskScanService.settle`）：先把整批按「回调结果 × taskType」分进三个互斥桶
  （判据顺序 = 原来的 if-else 顺序：成功 → 已达重试上限进死信 → 其余重试），然后**每桶一次写**。
  空桶不发语句。账本口径不变（`SettleOutcomeVO` 仍是「按回报归到哪一桶」），所以用例层的累加与上报不用改。
- **仓储**：`markSuccessBatch(slot, taskType, bizIds)` / `markRetryBatch(slot, taskType, retryTimes)` /
  `markDeadBatch(slot, taskType, bizIds)`，各自一条语句；`retryTimes` 是 `LinkedHashMap`（bizId 有序 ⇒
  CASE 与 IN 用同一个键序），重试桶的 `SET next_fire_time` 走 `CASE`——各行 `retry_count` 不同 ⇒ 退避不同，
  这是桶内唯一按行不同的列。
- **行级守卫原样保留**：三条批量 UPDATE 都带 `status IN ('WAITING','RETRYING')`。「捞批与结算之间的窗口里
  被改成终态的行」不会被翻转。代价是返回值从逐行布尔变成「本桶实际改到几行」——域服务把差值记进 INFO
  （只在**有差异**时打，不刷日志），**不是错误**。
- **路由**：`TimeoutTaskEntity` 加**不落库**的 `shardSlot`，`selectDueBatch` 填它（= 入参槽位）；
  结算批量语句用**批的**槽位塞 hint，不再逐行算自然键的槽位（一个批一个槽位，逐行算是白算）。
  缺 `shardSlot` 直接抛——没 hint 的批量 UPDATE 会被 SS 路由校验拒掉，显式报错比让它去广播强。

### 两个真问题（都是测试先抓出来的）

1. **`#{nextFireTimes[i]}` 在 MyBatis 里不是「取列表第 i 项」**：它把 `nextFireTimes[i]` 当参数名去参数里
   找，找不到就退化成把 `i` 当字面数字——`NumberFormatException: For input string: "i"`。
   必须写 `${i}` 让 OGNL 先算出下标再交给 `#{}` 绑定（SQL 注入口径不变：值仍是 `?` 占位绑定）。
2. **空桶也在发语句**（单测断言「200 条 → 2 次写」时抓到）：域服务与仓储两层都补了空桶短路，
   不然空桶的 `IN ()` 是语法错误。

### 验证

- **域层离线单测**（新增 `timeout-center-domain/src/test/.../TimeoutTaskScanServiceTest`，5 例；为此给
  domain 模块加了 test 依赖 junit-jupiter + assertj-core）：假仓储直接数调用次数，钉死「200 条 → 每桶一次
  写」「两种 taskType 各写各的桶、空桶不发」「批上没槽位立刻报错」「空批零写」。
  这是域层第一个不连栈的用例，与集成测试的分工写在类注释里。
- **集成测试**（新增 `TimeoutTaskBatchSettleIntegrationTest`，5 例，真库真 Dubbo）：批内成功/重试/死信混合
  各落各的状态 + 同组只一次调用；**满批 200 行**重试桶逐行核对退避（30s→封顶 1h，覆盖 CASE 的 200 分支）；
  **到点行在回调在途被改成终态后不被翻转**（`StubCallbackBehavior` 新增测试钩子，在「回调在途」这个窗口里
  改库——这正是批量语句最容易丢守卫的地方）；批量结算只落本槽位那张分表。
- 完成线：`mvn -pl timeout-center-app -am test` **37/37 绿** + domain **5/5 绿**（合计 42）。

### 已知残留 / 不在本票范围

- **缺「实际改到几行」的聚合观测**：现在只在有差异时打一条 INFO。要接指标（中心侧无监控平台属 spec §9 开放项）
  时，这三桶的返回行数是现成的埋点。别急着为它加 DTO。
- **真机整链路冒烟未重跑**：本票改的是结算写库形态，跨进程路由（动态 ReferenceConfig）与扫描 job 调度
  都没动，票 08/11 的冒烟结论仍然适用；要重跑需要 xxl-job admin（`bs-mysql` 里的 xxl_job 库 + admin 服务）。
- **`timeout-center-api` 一行未改**：所以单体侧（票 10/11 的实现）不需要跟着重编译，也没有版本纪律问题。
- **spec 未改**：这是 §6.3「批量条件 UPDATE」的字面落地（原文没说清是谁批量），措辞歧义留给 spec 的下一次
  修订；本条以本票与本票同步的两份文档为准。
