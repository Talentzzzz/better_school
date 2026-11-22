# 营销域 Glossary

better-school 营销服务（points / rebate / raffle / inventory 四域）的统一领域语言。本表只收领域词，不收实现细节。

## Language

### 积分与抽奖次数

**积分（Points）**:
用户通过行为返利、抽奖等获得的可消耗资产，按桶带过期时间。
_Avoid_: 金币、余额

**抽奖次数（Draw Count）**:
用户持有的抽奖资格余额，按桶带过期时间，抽奖主链路只扣次数、从不碰积分。

**入账（Credit）**:
把购买或返利获得的次数写成带过期时间桶的动作。

**扣分（Deduct）**:
从积分桶扣减余额的动作，购买次数时发生。

### 购买次数链路

**购买（Purchase）**:
用积分换抽奖次数的行为。本域统一用「购买」。
_Avoid_: 兑换（redeem）、交换

**购买单（Purchase Order）**:
一次购买的持久记录：既是业务留痕，也是这条链路的幂等键与状态机载体。单存在即积分已扣。
_Avoid_: 兑换单、任务、消息

**死信（Dead）**:
重试次数耗尽仍未能完成入账的购买单状态，需人工介入。

**对账（Reconciliation）**:
定期比对积分扣减流水与次数入账明细、找出并修复差异的兜底动作。

### 资金链路收口（fund-consistency）

**资金收口（Fund Closure）**:
把一条业务链路里「状态翻转 + 资金动作」两步合并为原子生效的改造：中间态不再依赖重试善意，宕机窗口与业务失败都不再产生永久不一致。
_Avoid_: 补一致性（泛称）、对平（对账是兜底动作不是收口本体）

**组合事务端口（Composite Transaction Port）**:
基础设施层的组合事务编排组件（`CompositeTxExecutor`）：一个 `TransactionTemplate`（REQUIRED）内编排多域动作，参与域仓储同为 REQUIRED 自动并入同一物理事务；任一步抛异常整事务回滚。消费方域在 `domain/<域>/adapter/port/` 声明端口接口，实现落基础设施层；域 A 不写域 B 的表，判据留在消费方域、端口只做编排。单体同库红利下的跨域原子性支柱，不需要状态表、不需要补偿 job。
_Avoid_: case 层包事务（纪律仍禁止）、分布式事务（不引入框架）、Saga/TCC（无反向补偿需求）

**孤儿预扣收敛（Orphan Pre-reserve Reconciliation）**:
抽奖「预扣→落库」跨库存合并器窗口产生的孤儿（预扣已提交、`draw_record` 未落库）由对账 job（`orphanPrereserveScan`，cron `0/30`、条件更新抢占、指数退避）在一个扫描周期内收敛：决策可重放→以原幂等键重放落库后半段（奖品可能漂移，接受）；决策不可重放→库存回补 + 抽奖次数退还。重试耗尽进死信。
_Avoid_: 人工 SQL 巡检（已被 job 取代，只剩死信处置）、自动补抽（不重放决策之外的任何东西）

### 库存对账兜底（draw-recon-round）

**对账轮（Reconciliation Round）**:
每日全量兜底对账 job（`drawReconScan`，xxl cron `0 0 3 * * ?`）：轧账四条不变量（两条台账守恒 + 正反双向配对）逐条登记差错，DEAD 孤儿单点名，二次核对后按分流表平账（正向漏结复用孤儿种单补收，守恒破坏/反向缺失告警人工）。纯旁路——业务表只读，写面仅差错表与轮次表；与逐笔孤儿预扣收敛 job 完全独立（互不依赖代码路径，只读其产物表）。
_Avoid_: 与孤儿预扣收敛共享代码路径（两层机制必须互不共享同一个 bug）、自动修守恒破坏/反向缺失（数据破坏级只告警人工）

**差错表（Variance Table）**:
对账轮发现的不一致登记（`draw_recon_variance`）：轮次号 + 检查类型 + 定位键 + 期望/实际值快照，唯一键（轮次, 检查类型, biz_id）保证调度重跑零副作用；状态机 `PENDING → CONFIRMED → AUTO_FIXED / RESOLVED_MANUAL / FALSE_ALARM`。
_Avoid_: 差错当轮直接处置（登记只 PENDING，下一轮复查仍在才 CONFIRMED——消化日切边界假差错）
