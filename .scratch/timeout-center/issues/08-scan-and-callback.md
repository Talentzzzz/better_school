# 08: 扫描 job 与批量回调闭环

**What to build:** 中心能自动触发到期任务并回调业务方。单 xxl-job（分片广播，`g % shardTotal` 认领，10s 周期，misfire FIRE_ONCE_NOW）扫描捞批（spec §5.1）→ 无分片键 SQL 走 HintManager 强制路由、与 complex 算法同一编号空间（spec §5.2）→ 每表循环拉批到空（批 ≤200，spec §5.3）→ 批内按 callback_group 分组、组间虚拟线程并行、每组合成一次 Dubbo 调用（spec §5.4）→ 中心按 `callbackGroup` 动态构建并缓存 ReferenceConfig，回调业务方实现的 `ITimeoutTaskCallback`，携带 `TimeoutTaskDTO` 三字段（spec §6.1/6.2）→ 按业务返回的成功/失败双清单就地 settle：成功项置 SUCCESS、失败项进 RETRYING（spec §6.3）。扫描防重四层落齐（表级归属/DISCARD_LATER/job 重试 0/在途重发业务幂等，spec §5.5）。

**Blocked by:** 07（工程骨架与任务 API）

**Status:** resolved

- [x] stub 业务方（实现 `ITimeoutTaskCallback` + `@DubboService`）注册一个 action_time 已过期的任务 → 一个扫描周期内收到批量回调
- [x] 回调 DTO 只含 taskType / bizId / actionTime 三字段
- [x] 批内多 group 分组发送：每 group 一次 Dubbo 调用，不跨组合并
- [x] 业务返回双清单 → 成功项 settle SUCCESS、失败项 settle RETRYING
- [x] 单批 >200 时循环拉批到空，无单 tick 批数上限
- [x] 新业务方接入（新增 group）中心零代码变动（动态 ReferenceConfig 生效）
- [x] 虚拟线程并行模型：扫描 fan-out 与回调发送均为虚拟线程，Hikari 池 ≥ 并行度

## Answer

2026-09-19 落地。新增：`timeout-center-api` 的回调契约（`ITimeoutTaskCallback` + `TimeoutTaskDTO` +
`BatchCallbackResult`）、domain 的扫描/结算（`ITimeoutTaskScanService` + 回调端口 + 三个 VO）、
infrastructure 的动态回调引用（`TimeoutTaskCallbackPort` + `TimeoutTaskCallbackReferences`）、
case 的 `TimeoutTaskScanCase`、trigger 的 `TimeoutTaskScanJob`、`sql/xxl-job-tasks.sql`。
完成线：`mvn -o test -pl timeout-center-app -am` **23/23 绿**（票 07 的 14 + 本票 9），
连续 4 轮全绿；另有**真实跑通**证据（见文末「实跑验证」）。

### 一处 spec 级偏离：分片路由从 complex 换成 hint（用户拍板）

spec §5.2 原写「写入走 complex 自定义算法 + 扫描走 HintManager 强制路由，两算法同一编号空间」。
**实测在 ShardingSphere 5.5.3 上做不到**，两条独立证据：

1. 一张表只能配一个 `tableStrategy`，两套分片算法无法共存；
2. complex 表上 `HintManager` 完全不生效——探针里把 hint 指到**错误槽位**，扫描照样把那行捞了回来
   （读字节码确认：`ShardingStandardRouteEngine` 只在 hint 策略下读 `HintManager`，
   见其 `isRoutingByShardingConditions` / `isGettingShardingValuesFromHint`）。

把证据摆给用户后，用户选「全量 hint 强制路由」：表策略 `strategy: HINT`，`TimeoutTaskRepository`
在**每次 DAO 调用前**把槽位塞进 `HintManager`（写入按键算、扫描用分片 job 给的槽位号），
`TimeoutTaskComplexShardingAlgorithm` 删除。spec §5.2 的「同一编号空间」因此**收敛成单点函数**
（`TimeoutTaskShardSlots`），不可能算岔。副作用是响的：写语句漏塞 hint 会被路由校验当场拒
（不会静默写错表），扫描漏塞则退化成 16 表广播（结果仍对，但表级归属失效）。

### 相对 spec 的几处口径取定（都写进了代码注释/arch 文档）

- **§5.3「回调独立 executor 提交即返回」按「网络并行、落库回槽位线程」实现**：本票先是让组任务
  自己落库，实测池一饱和就 3s 拿不到连接、被吞掉的 settle 让那些行同 tick 被再捞一次。
  改成组间并行只覆盖 Dubbo 调用、settle 回槽位线程串行后，并发 DB 需求恒等于槽位数（≤16），
  且**保证批内每行都被结算**（「循环拉批到空」的第二重保障）。§3.3 的「同步闭环」据此优先于 §5.3 的字面。
- **未回报项按失败**：业务方两边都没回报的项（含两份清单都报同一项）按失败重投——漏回报是契约违背，
  重一次无害、漏关一次是事故；同时它保证批内每行都离开到期集合，循环才能终止（spec §6.3 未规定此分支）。
- **回调引用 `retries=0` + `scope=remote`**：Dubbo 自带重试会让同一批在一次 tick 内重复回调，与
  「单次 2s 超时即失败、退避后由扫描重投」的口径打架；`scope=remote` 是因为中心与业务方永远不同进程，
  顺带让集成测试真的过一遍 tri 序列化（票 07 的 `Serializable` 坑就在这一层）。
- **池从 16 调到 24**（内外两层）：一槽一连接会把 16 个占满，注册方向与 ShardingSphere 自身的
  元数据连接就挤不进来（实测 `Connection is not available … active=16`）。24 = 1.5× 并行度，
  「池是真并发上限」的口径不变。
- **`BatchCallbackResult` 没按 §8.1 的 `XxxResponseDTO` 后缀**：spec §7.2 明确写了这个名字，
  契约里还有票 02/04 的「双清单 DTO 命名归还落地票」注记——按 spec 的具名优先（若要与仓库后缀统一，
  需改 spec §7.2，属另一次拍板）。

### code-review 双轴采纳项（两轴都出真货）

- Standards：`complex → hint` 改写后 4 处**陈旧文档**（mapper 头注释、`TimeoutTaskShardSlots` /
  hint 算法 javadoc、`schema-timeout-task.sql` 头注释）漏改，已同步；`ScanSummaryVO.describe()`
  的日志文案移回 trigger 的 job（域对象只管数字）；`references.get` 改名 `callbackOf`（返回的是代理）；
  仓储里三处重复的「秒级 now」提成私有方法；`awaitAll` 的 `Future<capture>` 泛型噪音用 `? extends` 收掉。
- Spec：`nextFireTimeAfterFailure` 的退避底数若配成 ≤0 会让 `next_fire_time` 贴住 tick 起始时刻、
  循环拉批永不终止——加了 ≥1s 兜底。
- 未采纳（判断项）：`awaitAll` 与票 07 `TimeoutTaskRegisterCase.await` 形状相似但异常策略不同，
  不为消重去动已落地的票 07 代码；域服务读自己的退避配置（`@Value`）保留。

### 已知残留（不在本票范围）

- **16 次封顶 → `FAILED` + 死信管理接口归票 09**：本票只做退避（30s 起指数翻倍、单次封顶 1h），
  重试次数不设上限。
- **业务侧接入**：outbox 提交通道归票 10、三路关单实现归票 11——本票只保证中心侧闭环，
  真实业务方（group = bs 应用名）尚未实现，故实跑验证只能验到「回调失败 → 退避重试」这一支。
- 回调在途被并发取消的行：settle 是条件 UPDATE，不会被回调结果翻回 SUCCESS（有代码注释，
  无用例覆盖——要在 stub 里制造「回调期间取消」，暂未做）。

## 实跑验证（本机，非测试）

`mvn -DskipTests package` → 跑 `sql/xxl-job-tasks.sql`（admin 里出现 `timeoutTaskScanJob`）→
`java -jar` 起中心（8083）→ 往 `bs_timeout` 的分表里直接插一行 `action_time` 已过期、group 指向
不存在的应用：

- admin 每 10s 触发一次，调度日志 `handle_code=200`，消息 = 「扫描完成：槽位 16 个、批次 N、任务 M 条…」；
- 中心日志出现 `[超时中心告警] 回调失败，本批 1 条全部按失败重投：group=no-such-app, 槽位=4`
  （降级路径 + 槽位归属都对）；
- 行状态 `WAITING → RETRYING`，`retry_count` 1→2，`next_fire_time` 恰好 +30s、+60s（退避口径对）。

## Comments
