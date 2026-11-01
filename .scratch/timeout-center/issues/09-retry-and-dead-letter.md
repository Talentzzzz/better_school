# 09: 重试退避与死信管理

**What to build:** 回调失败的任务有完整的重试-死信生命周期。失败项 `retry_count+1`、`next_fire_time = now + 退避`（30s 起指数翻倍、单次封顶 1h、共 16 次，数字可配），重试复用扫描 job 零额外设施（spec §6.5）；16 次超限置 FAILED 终态。中心管理接口：死信列表 / 单条与批量重发 / 手动取消（spec §6.5、§9）；重发走同一扫描 job 入口（FAILED → RETRYING，retry_count 清零重开一轮、不动 action_time，spec §3.3）。失败与进死信均有告警日志点。

**Blocked by:** 08（扫描 job 与批量回调闭环）

**Status:** resolved

- [x] 回调持续失败：按 30s→60s→120s… 指数退避逐次重试，单次间隔不超过 1h
- [x] 第 16 次失败后置 FAILED，不再被扫描捞起，有告警日志
- [x] 管理接口死信列表可查 FAILED 行
- [x] 管理接口单条与批量重发：FAILED → RETRYING，retry_count 清零，重开一轮重试（不动 action_time）
- [x] 管理接口手动取消：FAILED → CANCELED 终态

## Answer

2026-09-19 落地。**改**：域扫描服务的封顶分支（`TimeoutTaskScanService.settle` 三分支：成功 / 重试 /
进死信）、仓储 + DAO + mapper 的三条条件 UPDATE（`markDead` / `resendDeadLetter` / `cancelDeadLetter`）
与两条广播查询（`selectDeadLetters` / `countDeadLetters`）、`SettleOutcomeVO` 与 `ScanSummaryVO` 加
`deadCount`（并进扫描 job 的调度日志文案）、types 的 `ResponseCode` 补两个码、`application.yml` 补
`timeout-center.retry.{base-seconds,max-seconds,max-times}` 三项（原来是散落的 `@Value` 默认值）。
**新增**：domain 的 `service/deadletter/`（`ITimeoutTaskDeadLetterService` + `TimeoutTaskDeadLetterService`）、
case 的 `TimeoutTaskDeadLetterCase`、trigger 的 `task/http/`（`TimeoutTaskAdminController` +
同层 `dto/` 与 `response/` + `GlobalExceptionHandler`）。
完成线：`mvn -o test -pl timeout-center-app -am` **31/31 绿**（票 07/08 的 23 + 本票 8）。

### 两处设计分叉（都超出票面与 spec，用户拍板）

`/implement` 开场发现这两件事票面与 spec 都没定，摆出代价问用户（票 03 的「运维面…不进业务契约」与
仓库「DTO 放 api」的惯例在这里对撞），用户的选择：

1. **管理接口落 HTTP（8083），接口文件 + DTO + 统一响应体落 `timeout-center-trigger`**，**不动
   `timeout-center-api`**。载体判据来自 `backend/docs/architecture/timeout-center.md`（原文写「管理接口归票 09」，
   且 8083 是服务端口）；DTO 落点选 trigger 是为了让业务方引的契约 jar 保持「纯接口 + DTO」（spec §7.2），
   代价是 DTO 不在 api 模块、`Response`/`PageResult` 要在 trigger 自备一份（形状照单体与营销的同名类）。
2. **本版管理接口不加鉴权**：中心没有用户/JWT 设施，为它新造一套超出本票；记为已知缺口，
   并已把「管理接口鉴权」写进 spec §9 的开放项（与「告警接入监控平台」同批另议）。

### 相对 spec/票面的口径取定（都写进了代码注释与文档）

- **封顶语义**：`retry_count` 数的是**已失败次数**（含最后那次），第 16 次失败时它恰好到 16，这一行不再
  重试而是进 `FAILED`——即「16 次封顶」是 16 次失败，不是 16 次重试。上限可配（`retry.max-times`），
  配 ≤0 时兜底为 1（`Math.max(maxTimes, 1)`），与票 08 给退避底数加 ≥1s 兜底同一个理由：配置写错了
  不该让它变成「永不进死信」。
- **重发 = 立刻重开一轮**：`next_fire_time` 取 `now`（不是「沿用旧值」——旧值被最后一次退避推到了未来
  最多 1h 之后，沿用就等不到重投）；`action_time` 一律不动（spec §3.3 理论到期时刻不可变）。
- **重发/取消只打 `FAILED` 行**，与业务取消（Dubbo `ITimeoutTaskRegister#cancel` 只打未终态行）分成两条路：
  各管一段生命周期，语义不混。
- **死信列表是唯一不塞 hint 的查询**：它按状态翻、SQL 里没有分片键，故意裸调 DAO 让 ShardingSphere
  广播 16 张分表再归并（spec §5.2「死信聚合等跨表查询由 SS 广播归并白送」）。仓储类注释与
  `docs/db/timeout-task.md` 都点明了这条例外——塞 hint 会只查一张分表、翻不出全量死信。
- **`deadCount` 进了结算账与调度日志**（`成功 N / 重试 N / 进死信 N`）：票面只要求「告警日志点」，
  这条计数是超出票面的小加法，理由是进死信必须可观测、而扫描 job 的 handleSuccess 文案正是这个 tick
  对运维的主要窗口。**已知偏离**：若认为不该加，删 `SettleOutcomeVO`/`ScanSummaryVO` 各一列即可。

### code-review 双轴采纳项（两轴都出真货）

**Standards（无硬违规）**：

- **文档口径纠错**：`Response`/`dead-letter.md` 原写「与单体/营销各服务一致」——形状（`{code,info,data}` +
  HTTP 恒 200）确实一致，但**码值不一致**（本中心 `0000`/`9999`，单体与营销是 `200`/`999`）。
  已改成显式说明「只借形状不借值，沿本中心票 07 起的 `0001` 那套（也即 `backend/CLAUDE.md` §7 的示例），
  且本接口无前端消费、不存在要对齐的解包约定」。
- **删 `TaskKeyRequestDTO`**：与 `TaskKeyDTO` 逐字段重复（都是 taskType + bizId）。取消入参直接用
  `TaskKeyDTO`，少一个概念一个文件。
- **控制器两处逐字段拷贝循环抽成 `toKeyDTO`**（原来 successList/failList 各写一遍）。
- **重试告警从逐行改按批汇总**：`settle` 里每行一条 WARN 在「业务方整体不可用」时会一个 tick 刷出几千行
  （≤200 条 × 16 槽 × 每 10s），把真告警淹掉；改成每批一条带条数与最早重投时刻的 WARN。
  **死信仍逐行 ERROR**——一行只会死一次、低频，且每条都值得单独可查（要人工处理的就是它）。

**Spec**：

- **页码封顶 1000**（原来只夹了 `pageSize`）：列表是跨 16 分表的广播查询，`LIMIT/OFFSET` 的跳过行数
  每张分表各付一遍，而这个接口无鉴权——不夹等于给了个能把中心读垮的入口。已补测试与文档。
- **spec §9 我自己的重复行**：补记时新写的「开放项」行没有替掉原文那行，正文里同一句出现两遍，已删旧行。
- **spec §9 路由写法与实现不符**：原写 `/api/admin/timeout-tasks/{dead-letters,resend,cancel}`，
  实现里 resend/cancel 是 `dead-letters` 的子路径，已改正。

**未采纳（判断项，都写明了理由）**：`(taskType, bizId)` 继续以两个 String 穿仓储/DAO 签名
（票 07/08 既有形状，不为消重去动已落地代码）；`ScanSummaryVO`/`SettleOutcomeVO` 的位置参数构造
（同上，且各自只有一处构造点）；管理面复用 api 模块的双清单 DTO（api 是业务串行化对齐面，
用户已拍板不进——管理面 DTO 走 JSON，与 hessian2 的类检查/allowlist 无关，硬凑反而互相牵制）。

### 已知残留（不在本票范围）

- **管理接口无鉴权**，也没有网络层限制（记为 spec §9 开放项）。
- **回调在途被并发重发/取消的行**：settle 与重发/取消都是条件 UPDATE、各自只打该打的态（设计上不会
  互相翻），但**这个竞态没有用例覆盖**（票 08 同款残留）。
- **死信不自动归档/清理**：spec §3.5 首版不归档，死信同样只增不减，靠人工处理。

## 实跑验证（本机，非测试）

`mvn -DskipTests package` → `java -jar` 起中心（8083，dev 库 `bs_timeout`）→ 往分表里插两行 `FAILED`
（槽位 14 与 01，group 指向不存在的应用）：

- `GET /api/admin/timeout-tasks/dead-letters` 一次返回两条、`total=2` —— 两行在**不同物理分表**里，
  跨表广播归并真的生效（spec §5.2）；
- 中途先插错分表（04/09）时重发返回的是**失败清单**：hint 强制路由按自然键算出的槽位表里没有这行，
  写语句不会静默打到别的分表——这条意外反证了路由（也说明了为什么用例与冒烟都得把行摆对槽位）；
- `POST …/dead-letters/resend`（单条）→ `successList` 一条；库里立刻是 `RETRYING`、`retry_count=0`、
  `next_fire_time=now`，**`action_time` 仍是原值**；
- 10s 后**真实扫描 job** 把它捞起、回调失败（group 不存在）→ `retry_count=1`、
  `next_fire_time = +30s` ——「重发走同一扫描 job 入口」在真实调度下成立（spec §5.1）；
- `POST …/dead-letters/cancel` → `true`，行置 `CANCELED`；再翻列表 `total` 归 0；
- 三条告警日志点都在应用日志里出现（人工重发/取消 WARN、本批回调失败 WARN、进死信 ERROR）。

演示行与中途的探针脚本已清，dev 库无残留。

## Comments
