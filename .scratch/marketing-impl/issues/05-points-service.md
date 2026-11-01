# 05: 积分服务

**Spec:** `.scratch/marketing/spec.md` §「营销域」→「数据模型与行级时序」「接口口径与失败语义」+ `.scratch/marketing/issues/05-marketing-data-model.md`、`06-marketing-api-contract.md`

**What to build:** 积分服务的完整可跑链路：桶化积分模型落库、余额与流水两个只读接口、消费抽奖中奖事件加分、对外暴露兑换 Dubbo 接口（供抽奖服务同步调用）、变动事件发出。

**注意口径反转**：积分**不是**「余额列 + 条件更新」，而是**桶化（lot）模型**——没有余额列。

**Blocked by:** 01

**Status:** resolved

- [x] schema `bs_points`：`points_lot` / `points_consume` / `points_config`，外加本服务自己的 `outbox_message`；迁移 `022_points.sql`
- [x] 跨 schema 硬约定：只存 id、**不 join 单体库、不存昵称头像快照**
- [x] **桶化模型**：每笔入账 = 一行桶，带自己的 `expire_at`
- [x] 消费按 **FIFO**（`ORDER BY expire_at ASC, id ASC FOR UPDATE`）扣各行 `remain`，凑不够则**整事务回滚**
- [x] **余额 = 未过期桶的 `SUM(remain)`**——没有余额列；`FOR UPDATE` + 求和校验天然不可能为负
- [x] **独立过期**：读路径一律带 `expire_at > NOW()`（懒过期为准）；xxl-job 只做 `remain = 0` 的落账清理（**不删行**、留审计、**不发事件**）
- [x] `points_config.points_valid_days` 种子 **365**
- [x] `reason_type` 只允许 `LOTTERY_WIN` / `REDEEM_DRAW_COUNT` / `REDEEM_PRIZE`
- [x] 消费 `bs-lottery-drawn`（积分档加分），幂等键落 `uk_user_reason_ref(user_id, reason_type, ref_id)`
- [x] 发 `bs-points-changed` `{userId, delta, balanceAfter, reasonType, refId}`（走本服务 outbox + `@XxlJob("outboxScan")`，`MAX_RETRY_COUNT = 5`）
- [x] `GET /api/points/balance` → `{ balance }`
- [x] `GET /api/points/transactions` → `points_lot` ∪ `points_consume` 按时间归并的流水，`direction ∈ IN · OUT`；分页 `page`/`pageSize`（默认 `1`/`10`、上限 `50`）+ `PageResult{page,pageSize,total,list}`；`created_at DESC`
- [x] `IPointsExchangeService` 放 `backend/points/points-api`（DTO 同模块）；Provider 落 `bs-trigger/rpc/PointsExchangeDubboService`（`@DubboService`）；**幂等键 = 兑换单 id**
- [x] **积分服务对外只有两个只读接口、零写接口**（兑换的两个写接口挂抽奖服务）
- [x] 验收：手工发一条 `bs-lottery-drawn` → 桶入账、余额增加、流水出现、`bs-points-changed` 发出；构造过期桶验证懒过期；FIFO 跨桶消费与「凑不够即回滚」各一条用例
- [x] 后端集成测试放 `bs-app/src/test` 同构位置（本服务自己的测试模块）

## 实现要点（票 05）

### 落了什么

`backend/points/` 从「只有骨架」到「能跑完一整条积分链路」，外加共享设施：

| 面 | 落点 |
| --- | --- |
| SQL | `sql/schema-points.sql`（DDL 主体 + 种子）、`sql/init-marketing-dev.sql` / `init-marketing-test.sql`（建 `bs_points` / `bs_points_test`）、`sql/migrations/022_points.sql`（自建库、幂等可整体重跑） |
| 共享设施 | `scripts/test-env.sh` 起栈时建两个营销库、`reset-test-db` 一并重建；`docker-compose.yml` 头注释补命令与库名 |
| domain | `PointsLotEntity` / `PointsConsumeEntity` / `PointsTransactionVO`（+ 方向枚举 / 结果对象 / 两个消息 VO）、`IPointsRepository`、`IPointsAccountService` + Impl（FIFO 分配算法在这里，不在仓储里） |
| infrastructure | `IPointsDao` + 3 个 PO + `PointsMapper.xml`；outbox 三件套（`OutboxMessageStore` / `OutboxMessageSender` / `IOutboxDao`，**逐字照抄单体、只换包名**） |
| trigger | `PointsController`（两个只读 GET）、`PointsExchangeDubboService`（`@DubboService`）、`LotteryDrawnConsumer`、`PointsExpireScanJob`（`@XxlJob("pointsExpireScan")`） |
| api / types | `IPointsService` + `IPointsExchangeService` + 4 个 DTO + `PageResult`；`PointsReasonType` / `OutboxStatus` / `INSUFFICIENT_POINTS` 业务码 |
| app | datasource（`bs_points`）、mybatis、rocketmq、`dubbo.scan.base-packages`（Provider 不配这个 `@DubboService` 不导出）、`MybatisConfig` / `TransactionConfig` |
| 文档 | `docs/db/points.md`、`docs/api/points.md`、三个索引 README（**新建**，此前仓库没有）、`docs/testing.md` 加「营销服务的测试设施」、`marketing-services.md` 加「数据面」节 |

### 与设计票的三处偏离（都是落地时才暴露的，已回填）

1. **`points_consume` 的唯一键必须改**：设计票 05 §1 写的是 `uk_user_reason_ref(user_id, reason_type, ref_id)`，
   但 FIFO 跨桶消费会为**同一笔兑换**落多行（每桶一行），按用户维度的 uk 会把第 2 行 `INSERT IGNORE` 掉 ⇒
   被当成「已扣过」→ 少扣。实建 `uk_lot_reason_ref(lot_id, reason_type, ref_id)` + `idx_user_ref`。
   **⚠️ `lottery_draw_count_consume` 是同构的表，票 07 落地时必须照改**——已在上游票
   `.scratch/marketing/issues/05-marketing-data-model.md` §2①②⑧ 两处留 ⚠️ 注记。
2. **`transactions` 归并在 SQL 里做**（`UNION ALL` + `ORDER BY created_at DESC, id DESC` + LIMIT/OFFSET），
   设计票 06 §2 写的是「应用层按时间归并两表」。理由：应用层归并要各表多取 `offset+limit` 行再内存合并，
   翻到第 N 页的代价随页码涨。`id` 只作 tiebreak 保证分页稳定，不代表跨表可比（注释已写清）。
3. **建库文件是 `schema-points.sql` + `init-marketing-dev/test.sql` 三件套**，不是 spec 里写的单文件
   `init-marketing.sql`：spec 写于仓库 SQL 布局改版（`init.sql` → `init-dev.sql` / `init-test.sql` / `schema.sql`）之前，
   新布局把「建库」与「DDL 主体」拆开才能让开发库与测试库共用一份 DDL。票 07 加 lottery / referral 时按同一套扩。

### 实现细节里值得记的

- **「凑不够」不用回滚来表达**：先 `SUM` 加锁读到的桶集，不够就直接返回失败（一行都没写，事务空提交）；
  等价于「写一半再回滚」，但省掉一次回滚。
- **并发重复兑换的互斥点是 `INSERT IGNORE` 而不是那句预检**：RR 下对不存在的行取的间隙锁**彼此兼容**，
  两个并发事务都能过预检；真正挡住后到者的是「流水先行」——它插不进先到者持有的间隙，等对方提交后撞 uk 返回 0，
  整事务回滚。预检只是普通重试的快路径。注释已按这个真实机制写（原先写成「预检把并发串行化」是错的）。
- **撞 uk 的兜底分支不能回报余额**：那时循环里已扣过的桶在本事务内可见，读出来是脏值 ⇒ 事务内返回 `null`，
  由事务外重读真实余额后按幂等成功返回。
- 新增 `INSUFFICIENT_POINTS("4101")` 业务码；Dubbo 边**不抛异常**传业务失败（结果对象带 code/info/balance），
  抽奖侧要拿它映射成自己的失败码。真正的系统故障才抛出去交调用方重试。

### 验证怎么做的

`mvn -o install`（`backend/points/`，全模块）→ **11 个集成测试全绿**：

| 用例 | 覆盖 |
| --- | --- |
| `PointsLotteryWinIntegrationTest`（4） | 积分档入账（桶 / 余额 / 流水 / 事件四断言）、同 recordId 重投幂等、THANKS·PHYSICAL 忽略、未登录 401 |
| `PointsExpireIntegrationTest`（2） | 懒过期（余额排除、流水仍可查、读路径不写库）、`pointsExpireScan` 只置零不删行不发事件 |
| `PointsDeductIntegrationTest`（5） | FIFO 跨桶（两桶两行明细、金额分配正确）、积分不足一行不动、同兑换单重试幂等、非兑换类原因参数错误、跳过过期桶 |

测试设施落 `points-app/src/test`（独立 project 不能挤进 `bs-app`）：基类照 `CommunityApiTestBase` 的形状
（测试 Nacos 18848 写共享配置 + `System.setProperty` 注 Nacos 地址与 Dubbo 端口 + `@BeforeEach` TRUNCATE），
**登录态直接按共享密钥签发 JWT**（本服务没有登录链路）。
MQ 与 Dubbo 的 seam：消费者与 Provider 都当普通 Bean 直接调用（测试栈无 broker；真跨进程调用归票 08）。

### code-review 采纳项

两轴各出一批，采纳 3 条（其余为判断项，记录在「未采纳」）：

1. **Spec 轴（真 bug）**：撞 uk 兜底分支返回「事务内读到的余额」，是已扣未提交的脏值 ⇒ 改为事务内返回 `null`、
   事务外重读（见上「实现细节」第 3 条）。
2. **Spec 轴**：`existsConsumeForUpdate` 的注释宣称「间隙锁把并发重复调用串行化」——**间隙锁彼此兼容，不互斥**。
   已把注释改写成真实机制（互斥靠 `INSERT IGNORE` 撞 uk），`IPointsRepository` 接口与 mapper XML 两处同步。
3. **Standards 轴**：`PointsRepository` 上的 `@Slf4j` 无任何 `log.*` 调用（死注解）⇒ 删。

未采纳（记录理由）：

- **`PointsDeductResultVO` 与 `PointsDeductResponseDTO` 字段同形**：域对象不认识 api DTO（`backend/CLAUDE.md` §5），
  边界手工映射是有意为之，与单体 `WalletResponseDTO` 同法。
- **`PointsChangedMessage.reasonType` 是 `String` 而非枚举**：跨进程载荷用字符串更稳，也是单体 `ErrandMemberChangedMessage`
  的既有做法（`action.name()`）。
- **`PointsReasonType` 放 `points-types` 而非 `domain/model/valobj`**：§7 明写 types 放「常量 / 枚举 / 异常」，
  且 `points-api` 与 `points-infrastructure` 都要用；落 types 是唯一不制造反向依赖的位置。
- **`IPointsService`（HTTP 契约接口）放 `points-api`**：Spec 轴判为「轻度 scope creep」（该接口只被本服务
  controller 实现、不跨服务消费），但 `backend/CLAUDE.md` §5 是硬约定「Controller 要实现 api 里的 IXxxService」，
  且单体 `IWalletService` 同法（HTTP 契约与 Dubbo 契约同放 `-api`）。**保留**——票 06/07 照此办理，
  别一个服务实现、一个服务不实现。
- **`@XxlJob("outboxScan")` 在 infrastructure 而非 `trigger/job`**：逐字照抄单体（handler 名也可同名，各服务独立
  xxl appname）；服务自有的 `PointsExpireScanJob` 仍在 `trigger/job`。

### 已知波及 / 不在本票范围

- **种子没拆成独立的 `025_marketing_seed.sql`**：`points_config` 单行随 `022_points.sql` / `schema-points.sql` 一起落。
  spec §迁移与种子 的一 schema 一份 + 种子单独一份，是为「展示重置」服务；`025` 是三服务合并的种子文件，
  此刻只有一家可种，等票 07 落活动与奖品时再决定拆不拆。
- **「事件发出」的断言口径**：测试栈有 namesrv、没有 broker，发送必然 `No route info of this topic` 并被
  `OutboxMessageSender` 兜住（记录保持 PENDING、`retry_count` 增长）。所以验收只看 **outbox 落行 + 载荷正确**；
  真投递 + 单体落通知归票 09。
- **没有迁移测试**：`022_points.sql` 是「从无到有」建新 schema（不是给存量表加列），
  与 `schema-points.sql` 内容一致；等营销库真要演进时再按 `MigrationTestBase` 的先例补。
- **Dubbo 真跨进程调用未验**：本服务的 Provider 已在测试上下文里成功导出（`Register dubbo service … tri://…`），
  但消费者在抽奖服务，真调用归票 08（`check = false`，积分服务停掉时抽奖照常起）。
- **前端未动**：`/api/points/balance`、`/api/points/transactions` 的页面调用归票 11；Vite 代理由票 01 落好。
