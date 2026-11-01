# 08 营销侧内嵌规格写进文档（不落代码）

Type: task
Status: resolved
Blocked by: 02

## What to build

营销工程**零超时接入方**（实测：全工程 `Timeout` 零命中，`bs_marketing` 22 张表里只有 MQ 的 `outbox_message`），所以本期**不预先落表与代码**，只把「将来要落时怎么落」定死，避免下一个人重新设计：

1. **库与表**：16 张 `timeout_task_00..15` 落 `bs_marketing`（`marketing/sql/schema.sql`），`DB_COUNT = 1`，列与主体同构（无 `callback_group`）。
2. **域段落位**：`marketing` 是多域工程（points / drawcount / raffle / inventory / config），按 `backend/CLAUDE.md §7` 的「先按域分段」规则，超时是**技术域**——给出 `types.timeout` / `domain.timeout` / `infrastructure` / `trigger` 的确定落法，并说明它与既有 `common/outbox`、`types.common` 的边界。
3. **调度与并行**：`marketing-executor` 加一条 `SHARDING_BROADCAST` seed；并行度 `timeout.scan.parallelism` 与营销池（同为 10）的关系照 Notes 口径 5。
4. **边界判据**：写清既有三个营销扫描 job（`pointsExpireScan` / `drawCountBucketExpireScan` / `inventoryMerge`）与超时中心的分工——前两个是**批量到期落账 / 懒过期**（读路径以 `expire_at > NOW()` 为准，扫描只落账），**不是**「一对象一 deadline 一动作」，因此不属超时模型（Notes 口径 8）。并给出反例说明：若把每个积分桶建模成一条超时任务，行数会随桶数放大。

## 落点

`backend/marketing/docs/architecture/timeout.md`（新建，或并入营销现有架构文档）+ `CONTEXT.md` 相关词条（与票 09 的词条收口协调，避免两票改同一词条）。

## Answer

**已落地（2026-10-17，纯文档：零表、零代码）。** 规格主文档 = [`backend/marketing/docs/architecture/timeout.md`](../../../backend/marketing/docs/architecture/timeout.md)（新建，营销 `docs/` 下首个 `architecture/`），并从 `marketing/docs/README.md` 与 `backend/docs/architecture/marketing-services.md §6` 加了索引指针；`CONTEXT.md` 只新增「过期落账」一条。

四件事的落点：

1. **库与表**：16 张 `timeout_task_00..15` 进 `marketing/sql/schema.sql`（逻辑表 `timeout_task`、逐张同构、`uk_type_biz` / `idx_scan` / 5 态、无 `callback_group`），`DB_COUNT=1`、`TABLES_PER_DB=16`、`TOTAL_SLOTS=16`，槽位 `floorMod(hash(biz_id),16)`；营销是空表起步，**不需要内嵌化票 07 的存量搬迁**。**列名例外**：营销沿用主体的 `created_at`/`updated_at`（共享 DDL 模板 + 两个实现的 PO/mapper 要逐行可对照），落表时须在 `marketing/sql/schema.sql` 头注释与 `sql.md` 命名口径登记为**第二处例外**——这一条原票面没写，是执行中补的，否则营销库「全库无 created_at/updated_at」的注释与落表自相矛盾。
2. **域段落位**：超时是**技术域**，在每层各占 `timeout` 段——`types.timeout.TimeoutConstants`、`domain.timeout.*`（entity/valobj/`service.scan`+`service.deadletter`/`ITimeoutRepository`/`ITimeoutTaskCallbackPort`）、`infrastructure.timeout.*`（DAO+PO+`TimeoutRepository`+`sharding/` 三件套）、`usecase.timeout.*`（ScanCase / CallbackDispatcher / DeadLetterCase）、`trigger.timeout.job`（+ 需要时 `trigger.timeout.http`）；`marketing-api` 只放管理面 DTO、**不建 Dubbo 契约**。注册走**业务域自己的仓储方法**（实现内注入 `ITimeoutRepository`，同事务直插），业务域服务不注入超时仓储；回调端口在域层、Dispatcher 实现在 case 层（内嵌化票 04/05 只把这条例外写在实现类 javadoc）。**这条例外是 `backend/CLAUDE.md` §1/§3 的已知缺口**：规范没回写；营销真正落码时要一并补进 `backend/CLAUDE.md` §4（或架构文档），不能只留 javadoc。边界：`types.timeout` 不塞 `types.common`；`outbox_message` 是事件发布，**不能承载超时注册**（消费端是 MQ 订阅方，没有任何机制在 N 分钟后回调），回调动作里写 outbox 则正常。
3. **调度与并行**：`marketing/sql/xxl-job-tasks.sql` 加 `timeoutTaskScan` seed（`0/10 * * * * ?` + `SHARDING_BROADCAST` + `DISCARD_LATER` + 重试 0 + `FIRE_ONCE_NOW` + 启用）；认领 `slot % shardTotal == shardIndex && slot < TOTAL_SLOTS`；池 10 不动、`timeout.scan.parallelism=4`、宽度 `min(并行度, 认领表数)`，照 Notes 口径 5 与内嵌化票 04 的实测（峰值 4、Hikari active ≤ 4、留 ≥6）。另记两条落地前必须处理的：营销测试 profile **没有独立 `xxl.job.executor.appname`**（map 已知漂移，落真扫描前必须补 `-test`，否则 admin 每 10s 打测试库）；管理面路径不能照抄 `/api/admin/timeout-tasks/**`（前端该前缀代理到 8080），营销用 `/api/marketing/timeout-tasks/**` + `requireAdmin()`（照 `/api/marketing/config` 先例，不另设 `/admin` 段）。
4. **边界判据**：三个既有 job 都不收编——`pointsExpireScan` / `drawCountBucketExpireScan` 是**懒过期 + 批量落账**（读路径 `expire_at > NOW()`，扫描只把 `remain` 置 0），不是「一对象一 deadline 一动作」；`inventoryMerge` 是每秒结算流水线，根本不是 deadline。判据写成「deadline 已是业务列 + 动作同域 → 域内扫描；deadline 无处放 / 动作跨域 / 需要重试-退避-死信-管理面 → 超时中心」，并给了「每个积分桶一条超时任务」的反例：行数随累计入账笔数放大、任务行在 `idx_scan` 里躺一年、回调只写 `remain=0`（读路径本就忽略）、退避/死信变成非业务噪声、入账路径全部要多写一张表。

**与票 09 的词条收口**：票 09 改「超时任务」「注册 outbox」两条；票 08 只新增「过期落账」一条（营销积分段），两票不碰同一词条。

**执行中的事实更正**：票面「全工程 `Timeout` 零命中」经复核**不严格成立**——实际有 7 处无关字面命中（outbox 的 `scanTimeoutSeconds`、`IPointsRepository` javadoc 的 `payTimeoutMinutes`、RocketMQ 客户端副本的 `consumeTimeout`），但确实**零超时中心接入方**（无 `timeout_task` 读写、无 `ITimeoutTask*` 引用），结论不变。文档 §0 按复核后的口径写，不沿用「零命中」的说法。

**验证**：本次改动仅 Markdown（新文档 + 3 处文档链接 + CONTEXT 一条），未触碰任何代码/DDL/配置；相对链接逐条用脚本校验存在，无测试可跑。主体侧的 16 张表、`TimeoutTaskShardSlots`、扫描并行度等口径均按已 resolved 的票 02/04/05/06 引用，没有新事实。
