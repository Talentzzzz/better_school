# drawReconScan 探索报告（每日全量对账轮）

> 探索日期：2026-11-22 · 分支：`chat-ws-layering-rollout`
> 目标：为抽奖库存域新增每日全量对账 job（`drawReconScan`，见 `.scratch/draw-recon-round/spec.md`，spec 已就绪）。
> 本报告自包含，实现者无需重新探索。

---

## 0. Git 状态

- 当前分支：`chat-ws-layering-rollout`（与 origin 同步）。仅有的分支：`master`、`chat-ws-layering-rollout`（+ 各自 remote）。
- 工作区**不干净**：正在进行的 WS 分层重构改动（bs-infrastructure/ws、bs-trigger/ws 等 modified/deleted/untracked）。与营销库存域无关，但实现时注意不要把无关改动带进提交。
- 分支命名惯例：`<topic>[-rollout|-impl]`（如 `chat-ws-layering-rollout`；`.scratch/` 下的历史票目录名如 `fund-consistency-rollout`、`hot-row-merge-l2w256` 与分支同名同源）。建议本票分支：`draw-recon-round` 或 `draw-recon-round-impl`。
- 工作树中已存在未跟踪目录 `.scratch/draw-recon-round/`（内含 `spec.md`，即本票 spec）。

---

## 1. 要镜像的既有模式：孤儿预扣收敛 job

### 1.1 域服务 `RaffleOrphanPrereserveServiceImpl`

路径：`Z:/better_school/backend/marketing/marketing-domain/src/main/java/com/betterschool/marketing/domain/raffle/service/orphan/RaffleOrphanPrereserveServiceImpl.java`
接口：同目录 `IRaffleOrphanPrereserveService.java`

分层纪律（类 javadoc 原文）：
> 本类不注入 `TransactionTemplate`，每一步都是仓储/端口自带的原子用例，跨步一致性由对账单状态机 + 幂等（落库 uk_record_id / 次数桶 uk_user_reason_ref）承担。

关键签名与结构：

```java
@Service @RequiredArgsConstructor
public class RaffleOrphanPrereserveServiceImpl implements IRaffleOrphanPrereserveService {
    static final Duration SEED_THRESHOLD = Duration.ofMinutes(10);   // 孤儿判定阈值
    static final int COMPENSATE_MAX_RETRY = 5;                        // 第 5 次尝试失败置 DEAD
    private static final List<Duration> COMPENSATE_BACKOFF = List.of(1,2,4,8,16 分钟);

    private final IRaffleRepository raffleRepository;
    private final IRaffleDrawService raffleDrawService;
    private final IDrawCountAccountService drawCountAccountService;
    private final IInventoryDeductPort inventoryDeductPort;

    @Override public int reconcile(int limit) {          // job 唯一入口
        seedOrphanPrereserves(limit);                     // 段一：种单
        return compensateDueOrders(limit);                // 段二：补偿
    }
    @Override public int seedOrphanPrereserves(int limit)   // 扫孤儿 → INSERT IGNORE 种对账单
    @Override public int compensateDueOrders(int limit)     // listDue → 抢占 → converge
    private void converge(DrawOrphanPrereserveOrderEntity order)
    private void restituteIfDrift(...)                     // 决策漂移时回补原奖品预扣
    private void restituteOrphan(...)                      // restitutePrereserve 守卫 false → ERROR 留人工，不抛
    private boolean isActivityNotReplayable(AppException e) // 4301/4302 → 走回补路径
    private LocalDateTime nextBackoffAt(Integer retryCount)
}
```

主流程要点：
1. **种单**：`inventoryDeductPort.listOrphanPrereserves(before, limit)`（端口翻译库存域候选）→ `raffleRepository.queryUserIdByDrawCountRef(recordId)` 反查 user_id（查不到 = 数据破坏，ERROR 跳过本周期）→ `insertOrphanPrereserveOrder`（INSERT IGNORE，返回 false = 已种过）。
2. **补偿**：`listDueOrphanPrereserveOrders(limit)` → 每单先 `claimOrphanPrereserveOrder(id, nextBackoffAt(retryCount))` 条件更新抢占（0 行 = 被抢/状态漂移，跳过）→ `converge`。
3. **converge 两路**：
   - 快路：`queryRecordByRecordId` 记录已落 → `restituteIfDrift` + `finishOrphanPrereserveOrder` → DONE。
   - 路径①：`raffleDrawService.replayDrawAfterPrereserve(userId, recordId)`（决策可重放，以原幂等键重放落库后半段）→ 漂移回补 → DONE。
   - 路径②：抛 `AppException` 且 code ∈ {LOTTERY_ACTIVITY_ENDED, LOTTERY_ACTIVITY_NOT_STARTED} → `restituteOrphan`（经端口回补库存）+ `drawCountAccountService.refundForOrphanDraw(userId, recordId, qty)`（次数退还，桶唯一键幂等）→ DONE。
4. **失败重试**：converge 抛异常 → 退避已由 claim 写入 next_retry_at → 重试耗尽（retry_count+1 >= 5）→ `markOrphanPrereserveOrderDead(id)` → **只 ERROR 日志 + 人工 SQL 置回 ORPHAN，不建告警通路**（这是本次 drawReconScan 要补的洞）。

`restitutePrereserve` 守卫（库存域实现，见 §2.4）：守卫不过返回 false = 已回补过或不变量破坏，调用方 ERROR 后继续收敛，不再重试该条。

### 1.2 xxl-job 触发层 `OrphanPrereserveScanJob`

路径：`Z:/better_school/backend/marketing/marketing-trigger/src/main/java/com/betterschool/marketing/trigger/raffle/job/OrphanPrereserveScanJob.java`

```java
@Slf4j @Component @RequiredArgsConstructor
public class OrphanPrereserveScanJob {
    private static final int SCAN_LIMIT = 100;
    private final IRaffleOrphanPrereserveService raffleOrphanPrereserveService;

    @XxlJob("orphanPrereserveScan")
    public void execute() {
        int converged = raffleOrphanPrereserveService.reconcile(SCAN_LIMIT);
        if (converged > 0) log.info("xxl-job: 孤儿预扣对账收敛 {} 笔", converged);
    }
}
```

模式要点（javadoc 原文）：
- 只转调域扫描入口（"第二缝 = 触发层只测一条断言的先例"）。
- **不调 `XxlJobHelper`**——handler 正常返回即成功（与 better-school 的 `SearchIndexRebuildJob` 同口径），**触发层可在无 xxl 上下文的环境直调**（集成测试直接 new/调 execute()）。
- 对照：`DrawCountPurchaseScanJob`（同目录）调了 `XxlJobHelper.handleSuccess()`——新 job 应照 OrphanPrereserveScanJob 的不调版本。
- cron 0/30 由 seed SQL 配置，不在代码里。

### 1.3 对账单表 `draw_orphan_prereserve_order`

- DDL：`Z:/better_school/backend/marketing/sql/schema.sql` L95-110（全文见 §7 幂等小节引用）。
  - 列：`id, record_id(VARCHAR36, uk_record_id), award_id, user_id, quantity, state TINYINT, retry_count INT, next_retry_at DATETIME, finish_time, create_time, update_time`
  - `state`：`0 待收敛(ORPHAN) / 1 已收敛(DONE) / 2 死信(DEAD,重试耗尽，人工介入)`
  - `UNIQUE KEY uk_record_id (record_id)` + `KEY idx_state_next_retry (state, next_retry_at)`
- 状态枚举：`Z:/better_school/backend/marketing/marketing-types/src/main/java/com/betterschool/marketing/types/raffle/DrawOrphanPrereserveState.java`（ORPHAN(0)/DONE(1)/DEAD(2)，三态刻意无 FAILED/PROCESSING）。
- 实体：`marketing-domain/.../raffle/model/entity/DrawOrphanPrereserveOrderEntity.java`
- PO：`marketing-infrastructure/.../raffle/dao/po/DrawOrphanPrereserveOrderPO.java`
- DAO：`marketing-infrastructure/.../raffle/dao/IDrawOrphanPrereserveOrderDao.java`（`insertIgnoreOrder / listDue / claim / finish / markDead`）
- Mapper XML：`Z:/better_school/backend/marketing/marketing-app/src/main/resources/mybatis/mapper/raffle/DrawOrphanPrereserveOrderMapper.xml`
  - 种单：`INSERT IGNORE INTO ... VALUES (...)`（撞 uk_record_id 静默跳过）
  - listDue：`WHERE state = 0 AND (next_retry_at IS NULL OR next_retry_at <= NOW()) ORDER BY id ASC LIMIT #{limit}`
  - claim：`UPDATE ... SET retry_count = retry_count + 1, next_retry_at = #{nextRetryAt} WHERE id = #{id} AND state = 0 AND (next_retry_at IS NULL OR next_retry_at <= NOW())`
  - finish：`SET state = 1 WHERE record_id = #{recordId} AND state <> 1`（人工重置回 ORPHAN 的 DEAD 单也可达，自愈）
  - markDead：`SET state = 2 WHERE id = #{id} AND state = 0`

---

## 2. 库存域表与实体

### 2.1 `draw_strategy_award_inventory`（奖品库存统一台账）

- DDL：`Z:/better_school/backend/marketing/sql/schema.sql` L299-313。
  - 列：`award_id(uk_award_id, 1 奖品位 1 账户), type(PHYSICAL/VIRTUAL), surplus_qty(-1=不限量), waiting_qty(仅 PHYSICAL), issued_qty, value_qty(仅 VIRTUAL), state TINYINT(0 open/1 exhausted/2 closed), create_time, update_time`
- PO：`Z:/better_school/backend/marketing/marketing-infrastructure/src/main/java/com/betterschool/marketing/infrastructure/inventory/dao/po/AwardInventoryPO.java`
- DAO：`marketing-infrastructure/.../inventory/dao/IAwardInventoryDao.java`
- Mapper XML：`Z:/better_school/backend/marketing/marketing-app/src/main/resources/mybatis/mapper/inventory/AwardInventoryMapper.xml`
  - `restitutePhysical`（L60）：`waiting → surplus` 条件转移，`WHERE ... waiting_qty >= #{quantity}`；不限量户（surplus=-1）不动 surplus；exhausted 回 open，closed 不自动重开。
  - `restituteVirtual`（L71）：`issued → surplus` 条件回退，`WHERE ... issued_qty >= #{quantity}`。
  - `deductPhysical / deductVirtual / transferWaitingToIssued / allocateIn(ODKU upsert)` / 读侧 `selectByAwardIds`。

### 2.2 `draw_strategy_award_inventory_detail`（统一流转明细）

- DDL：schema.sql L315-327。列：`award_id, flow_type TINYINT, quantity BIGINT, biz_type VARCHAR(30), biz_id VARCHAR(36), create_time, update_time`；`UNIQUE KEY uk_award_biz (award_id, biz_type, biz_id)` + `KEY idx_award (award_id)`。
- PO：`Z:/better_school/backend/marketing/marketing-infrastructure/src/main/java/com/betterschool/marketing/infrastructure/inventory/dao/po/AwardInventoryDetailPO.java`
- biz_type 枚举：`Z:/better_school/backend/marketing/marketing-types/src/main/java/com/betterschool/marketing/types/inventory/InventoryBizTypes.java`
  - `LOTTERY_DRAW`（预扣，flow_type=1，biz_id = draw_record.record_id）/ `LOTTERY_CLAIM`（确认，flow_type=2）/ `AWARD_ALLOCATE`（分配转入，flow_type=0，biz_id = 分配单号）。
- flow_type 语义（schema 注释 + `docs/db/inventory.md`）：`0 分配转入 / 1 预扣（PHYSICAL 进 waiting，VIRTUAL 直接发放）/ 2 兑换确认（仅 PHYSICAL）`。flow_type 与 biz_type 有派生关系但两列都保留，为对账 SQL 直接过滤。
- Mapper XML：`Z:/better_school/backend/marketing/marketing-app/src/main/resources/mybatis/mapper/inventory/AwardInventoryDetailMapper.xml`
  - **孤儿候选扫描 SQL（对账判据的直接先例，L68）**：
    ```sql
    SELECT ... FROM draw_strategy_award_inventory_detail
    WHERE flow_type = 1 AND biz_type = 'LOTTERY_DRAW'
      AND create_time < #{before}
      AND NOT EXISTS (SELECT 1 FROM draw_record WHERE record_id = biz_id)
    ORDER BY id ASC LIMIT #{limit}
    ```
    （注释明确：NOT EXISTS 反查的是对账判据的另一张域表，只读反查不写，跨域表查询有先例。）
  - `batchInsertIgnoreDeductDetails / listExistingDeductDetails / existsDeductDetail / insertIgnoreClaimDetail / insertIgnoreAllocateDetail / deleteDeductDetail`。

### 2.3 `draw_record`（抽奖记录）

- DDL：schema.sql L203-222。列含：`record_id(VARCHAR36, uk_record_id), user_id, activity_id, strategy_id, award_id, award_name, award_desc, award_type, level, inv_id(VARCHAR36 NULL, 发放来源快照，无库存来源 NULL), state(待兑换/已兑换/已发货/已完成)`；`KEY idx_user_created, idx_inv_id`。
- PO：`Z:/better_school/backend/marketing/marketing-infrastructure/src/main/java/com/betterschool/marketing/infrastructure/raffle/dao/po/DrawRecordPO.java`
- `award_type` 取值：`VIRTUAL / PHYSICAL / THANKS`（旧 POINTS 归入 VIRTUAL）。**THANKS = 谢谢参与占位**：`award_rate` 计入概率池、`inv_id='0'` 无库存、落库即已完成、不发 `bs-lottery-drawn` 事件、不进"我的奖品"（schema.sql L176/L215、`docs/api/lottery.md`）。对账反向配对时 THANKS 记录无预扣明细是正常态。

### 2.4 守恒不变量（B3 等）——对账 SQL 的公式出处

**代码内注释**（`AwardInventoryDetailMapper.xml` L79，deleteDeductDetail 上方）：
> 删除孤儿预扣明细行：随回补同事务执行（**守恒判据 B3：waiting + issued = Σ flow_type=1 quantity**，台账转回而不删行即破等式）。

**文档版 §0 守恒口径**（`Z:/better_school/backend/marketing/docs/db/inventory.md` L83-94）：
```text
奖品库存（PHYSICAL 行）： waiting_qty + issued_qty == SUM(detail.flow_type = 1)
奖品库存（VIRTUAL  行）： issued_qty               == SUM(detail.flow_type = 1)
确认行（flow_type = 2）：在 waiting/issued 两列之间搬，和不变，不进上面等式
分配行（flow_type = 0）：不进上面两个等式（它只在 surplus 与池之间搬运）
池侧：surplus 转出量 == SUM(inventory_allocation_flow.quantity)（不限量池不减、流水照记）
```
文档还写明："守恒等式由集成测试断言锁住（`InventoryQuotaConservationIntegrationTest` 的 B0–B3），不留在运行时读数里"——**对账轮正是把它们变成每天真跑的 SQL**。
- 测试出处：`Z:/better_school/backend/marketing/marketing-app/src/test/java/com/betterschool/marketing/integration/inventory/InventoryQuotaConservationIntegrationTest.java`（B0 分配守恒 / B2 三列守恒 `surplus + waiting + issued == 分配转入量` / B3 明细等式）。
- 回补实现：`Z:/better_school/backend/marketing/marketing-domain/src/main/java/com/betterschool/marketing/domain/inventory/service/InventoryQuotaServiceImpl.java` 的 `restitutePrereserve(awardId, bizType, bizId, quantity)`（L212：低频直落不进合并器；台账转移 + 明细行删除的原子性交给仓储一个事务，落库真身在 `InventoryQuotaFlushService`）；`listOrphanPrereserves(before, limit)`（L201）。接口 `IInventoryQuotaService.java` L98/L115。
- 候选 VO：`marketing-domain/.../inventory/model/valobj/OrphanPrereserveCandidateVO.java`。

### 2.5 既有聚合 SQL 先例

- `Z:/better_school/backend/marketing/marketing-app/src/main/resources/mybatis/mapper/raffle/DrawCountMapper.xml` L31-35：
  ```sql
  <!-- 剩余次数 = 未过期且未扣完的桶 remain 之和：没有余额列 -->
  <select id="sumAvailableRemain" resultType="int">
      SELECT COALESCE(SUM(remain), 0) FROM draw_count_bucket
      WHERE user_id = #{userId} AND remain > 0 AND expire_at > NOW()
  </select>
  ```
- `PointsMapper.xml` L65 有 `GROUP BY user_id` 聚合。
- 物理库存对账读数先例：`PhysicalQuotaIntegrationTest` L365-377（"对账（票 08）：池转出量 == 分配流水"断言）。
- 目前 mapper 里**没有按 award_id 分组的 GROUP BY 聚合**——对账轮的四条聚合 SQL 是新增。

---

## 3. 营销通知链路（告警通道）

现有链路 = **outbox → RocketMQ → 单体消费 → 通知中心**，营销侧不直调单体：

1. **营销侧发送**：`Z:/better_school/backend/marketing/marketing-infrastructure/src/main/java/com/betterschool/marketing/infrastructure/common/outbox/OutboxMessageStore.java`
   - `save(String destination, Object payload)` / `save(destination, payload, delayLevel[, delayMs])`：事务内插 `outbox_message` 行（须被业务事务包裹），事务提交后 `afterCommit` 异步首发；`OutboxMessageSender` 兜底 + xxl `outboxScan`（0/30）补发。
   - 既有事件示例：`LotteryDrawnMessage`（topic `bs-lottery-drawn`，`marketing-types/.../common/LotteryDrawnMessage.java`、topic 常量 `Constants.LOTTERY_DRAWN_TOPIC`）、`PointsChangedMessage`（topic `bs-points-changed`）。中奖事件由 `RaffleRepository` 与业务写同事务落 outbox（L370：THANKS 档不发事件）。
2. **单体消费**：`Z:/better_school/backend/better-school/bs-trigger/src/main/java/com/betterschool/trigger/listener/LotteryDrawnConsumer.java`（`consumerGroup = "bs-monolith-lottery-drawn-group"`）→ `MarketingNoticeCase.notifyLotteryDrawn(message)`。
3. **落通知**：`Z:/better_school/backend/better-school/bs-case/src/main/java/com/betterschool/usecase/marketing/MarketingNoticeCase.java`
   - `notificationService.publish(NotificationEventMessage.builder()...dedupKey("MARKETING:...").build())`；通知落库由 `NotifyEventConsumer` 消费 `bs-notify-event` 完成；幂等 = `notification.dedup_key`；scope 恒 `BOTTOM_SYSTEM`、`ref_type = MARKETING`、type 用 `NotificationTypeVO` 营销枚举（`bs-domain/.../notification/model/valobj/NotificationTypeVO.java` L22：LOTTERY_WON 等，"谢谢参与不发事件不落通知"）。

对账轮告警的落点（spec Implementation Decisions）："复用现有营销通知链路（对账轮发现 CONFIRMED 差错 / DEAD 孤儿单时发通知），消息内容带轮次、类型、定位键、快照"。实现方向：营销侧新增事件消息类型 + 同事务写 outbox（或差错登记事务内写），单体加对应 consumer 方法落通知（在 `MarketingNoticeCase` 或新 case 类扩展 `NotificationTypeVO` 枚举值）。注意：孤儿收敛 job 现状是"死信只 ERROR 日志 + 人工 SQL 重置，不建告警通路"（`IRaffleOrphanPrereserveService.java` L14、`OrphanPrereserveScanJob.java` L14 明确声明），这是本次要打通的既有缺口。

---

## 4. 模块结构与新增代码落点

营销是独立 Maven 多模块工程：`Z:/better_school/backend/marketing/`（聚合 pom modules：marketing-types → api → domain → case → infrastructure → trigger → app）。注意 `backend/better-school/`（bs-* 七模块单体）是另一个工程——**对账轮全部落 marketing 工程**（差错表在 bs_marketing 库）。

| 内容 | 落点（`Z:/better_school/backend/marketing/` 下） |
| --- | --- |
| 域服务接口+实现 | `marketing-domain/src/main/java/com/betterschool/marketing/domain/raffle/service/recon/`（新建，参照 `service/orphan/` 形态；如需扩库存侧端口，接口在 `domain/raffle/adapter/port/inventory/`） |
| 触发层 job | `marketing-trigger/src/main/java/com/betterschool/marketing/trigger/raffle/job/DrawReconScanJob.java`（照 `OrphanPrereserveScanJob` 薄壳） |
| DAO + PO | `marketing-infrastructure/src/main/java/com/betterschool/marketing/infrastructure/raffle/dao/`（一表一 DAO，如 `IDrawReconVarianceDao`）+ `dao/po/` |
| Mapper XML | `marketing-app/src/main/resources/mybatis/mapper/raffle/DrawReconVarianceMapper.xml`（**SQL XML 全部在 marketing-app 模块**） |
| 类型/状态枚举 | `marketing-types/src/main/java/com/betterschool/marketing/types/raffle/` |
| SQL DDL | `marketing/sql/schema.sql`（追加 `CREATE TABLE IF NOT EXISTS draw_recon_variance ...`；单文件幂等 seed，无独立迁移目录） |
| xxl-job 注册 | `marketing/sql/xxl-job-tasks.sql`（追加 INSERT，handler 名 `drawReconScan`，cron `0 0 3 * * ?`，照 L91-103 raffleCacheWarmup 的每日任务形状；本脚本幂等 WHERE NOT EXISTS） |
| 表文档 | `marketing/docs/db/`（draw.md / inventory.md / points.md，可补 recon.md 或并入 draw.md）；根目录 `Z:/better_school/sql.md` 是全库 DDL 汇总文档（"唯一事实源"，§2 营销 18 张表） |

---

## 5. Repository/Port 分层模式（抽奖域实例）

- **端口（domain 层声明）**：`Z:/better_school/backend/marketing/marketing-domain/src/main/java/com/betterschool/marketing/domain/raffle/adapter/port/inventory/IInventoryDeductPort.java`
  - 跨域写组合不在域服务间直接注入（CLAUDE.md §1）；端口把库存域值对象翻译成自己形状（`record OrphanPrereserveRef(String awardId, String recordId, long quantity)`；`enum Outcome`）。
- **实现（infrastructure 层）**：`Z:/better_school/backend/marketing/marketing-infrastructure/src/main/java/com/betterschool/marketing/infrastructure/raffle/adapter/port/inventory/InventoryDeductPort.java`
  - 纯透传委托 `IInventoryQuotaService`，在这里填 `AwardInventoryType` 与 `InventoryBizTypes.LOTTERY_DRAW`（抽奖域不认识库存表列）。
- **仓储（domain 接口）**：`marketing-domain/.../raffle/adapter/repository/IRaffleRepository.java`
  - 孤儿对账相关方法（L207-236）：`insertOrphanPrereserveOrder / listDueOrphanPrereserveOrders / claimOrphanPrereserveOrder / finishOrphanPrereserveOrder / markOrphanPrereserveOrderDead / queryUserIdByDrawCountRef`，另有 `queryRecordByRecordId`（L88）。
- **仓储实现**：`marketing-infrastructure/.../raffle/adapter/repository/raffle/RaffleRepository.java`（L517-580 孤儿段：Entity↔PO 手工映射，INSERT IGNORE 结果 `> 0` 判布尔）。

---

## 6. 幂等/唯一键惯例

DDL 唯一键声明（`Z:/better_school/backend/marketing/sql/schema.sql`，全部带语义 COMMENT）：
- `draw_orphan_prereserve_order`：`UNIQUE KEY uk_record_id (record_id) COMMENT '对账幂等键：一次孤儿预扣一张对账单，种单 INSERT IGNORE（DONE/DEAD 后扫描重种按幂等跳过）'` + `KEY idx_state_next_retry (state, next_retry_at)`
- `draw_strategy_award_inventory_detail`：`UNIQUE KEY uk_award_biz (award_id, biz_type, biz_id)`
- `draw_count_purchase_order`：`uk_purchase_order_id` + `idx_state_next_retry`
- `points_bucket / draw_count_bucket`：`uk_user_reason_ref (user_id, reason_type, ref_id)`（退还幂等键）
- `outbox_message`：`uk_msg_id`

插入撞键的两种处理（择一按语义）：
1. **INSERT IGNORE**（扫描/批量种单、明细流水）：撞键静默跳过、按成功回放——孤儿种单（`DrawOrphanPrereserveOrderMapper.xml` insertIgnoreOrder）、库存明细三个 insert。
2. **抛 `DuplicateKeyException` 由调用方转幂等推进**（并发互斥点）：购买单落单（`IDrawCountPurchaseOrderDao.java` L17 注释"撞 uk_purchase_order_id 抛 DuplicateKeyException（并发同 token 的唯一互斥点，不用 INSERT IGNORE）"，捕获于 `RaffleDrawCountPurchaseServiceImpl.java` L66）、抽奖记录落库（`RaffleDrawServiceImpl.java` L280）。
3. 另有 **ON DUPLICATE KEY UPDATE**（`allocateIn` 台账 upsert）。

对账差错表应选：幂等唯一键 `(轮次, 检查类型, biz_id)` + INSERT IGNORE（或先查后插 + 撞键回放）。

命名口径（根 `sql.md` 头部）：每表 `id BIGINT NOT NULL AUTO_INCREMENT` 纯物理自增开头、业务键单独成列配 `uk_<列>`、表尾固定 `create_time/update_time DATETIME NOT NULL`（无默认值，由应用写，DB NOW() 例外见 inventory 明细）、`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4` + 表 COMMENT。

---

## 7. GLOSSARY.md（`Z:/better_school/GLOSSARY.md`，50 行）

与本票相关的词条（全文摘录）：
- **死信（Dead）**：重试次数耗尽仍未能完成入账的购买单状态，需人工介入。
- **对账（Reconciliation）**：定期比对积分扣减流水与次数入账明细、找出并修复差异的兜底动作。
- **孤儿预扣收敛（Orphan Pre-reserve Reconciliation）**：抽奖「预扣→落库」跨库存合并器窗口产生的孤儿（预扣已提交、`draw_record` 未落库）由对账 job（`orphanPrereserveScan`，cron `0/30`、条件更新抢占、指数退避）在一个扫描周期内收敛：决策可重放→以原幂等键重放落库后半段（奖品可能漂移，接受）；决策不可重放→库存回补 + 抽奖次数退还。重试耗尽进死信。_Avoid_: 人工 SQL 巡检（已被 job 取代，只剩死信处置）、自动补抽（不重放决策之外的任何东西）。
- **资金收口（Fund Closure）**：把「状态翻转 + 资金动作」合并为原子生效的改造；_Avoid_ 补一致性、对平（对账是兜底动作不是收口本体）。
- 注意：GLOSSARY 目前**没有**「预扣」「回补」「台账」的独立词条（这些词散见于各词条正文与代码注释：预扣=预占库存待确认，回补=把预扣的台账量转回去，台账=三列计数的账户行）。新词条（对账轮/差错表）落地时应同步补 GLOSSARY。

---

## 8. 其他实现者须知

- **合并器旁路**：`InventoryQuotaServiceImpl` 注释明确"孤儿预扣对账（低频对账动作，不进合并器）"——对账轮的只读聚合同样不应进合并器，直接 mapper 查询。
- **InventoryQuotaFlushService**：`Z:/better_school/backend/marketing/marketing-infrastructure/src/main/java/com/betterschool/marketing/infrastructure/inventory/adapter/repository/InventoryQuotaFlushService.java`——库存落库事务真身（flushDeductGroup / flushClaimGroup / restitutePrereserve 的仓储实现同在此域的 `InventoryRepository`：`marketing-infrastructure/.../inventory/adapter/repository/InventoryRepository.java`）。
- xxl-job 执行器：app_name `marketing-executor`（`marketing-app/src/main/java/com/betterschool/marketing/config/XxlJobConfig.java`；本地无调度中心时注册失败仅日志告警）。cron 建议值口径写在 `xxl-job-tasks.sql` 头注。
- 相关文档：`marketing/docs/db/inventory.md`（守恒 §0 + 孤儿预扣节 + 排查 SQL）、`marketing/docs/db/draw.md`、`marketing/docs/api/lottery.md`。
- spec 全文在 `Z:/better_school/.scratch/draw-recon-round/spec.md`（含四条不变量公式、差错表 `draw_recon_variance` 状态机 `PENDING → CONFIRMED → AUTO_FIXED / RESOLVED_MANUAL / FALSE_ALARM`、不写新测试的决策）。
