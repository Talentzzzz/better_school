# 超时中心内嵌化：上线 / 回滚清单（票 07）

> 状态：定稿（2026-10-17）。适用改造：`backend/timeout-center/` 的存量在途任务切换到
> `better-school` 进程内超时中心（[`timeout-inline`](../../../.scratch/timeout-inline/map.md)）。
> 执行工具：[`backend/scripts/timeout-cutover-migrate.py`](../../scripts/timeout-cutover-migrate.py)；
> 背景与决策见[票 07](../../../.scratch/timeout-inline/issues/07-inflight-migration.md)。

## 0. 结论（先看这 8 行）

1. **不做双跑**。旧中心继续跑需要业务侧继续暴露 Dubbo 回调 Provider；票 05 已把它删掉
   （`TimeoutTaskCallbackDubboService` + `timeout-center-api` 依赖），双跑窗口里的回调会全部
   `No provider available`，把在途行重试进死信。旧口径「一次性下线、不留双跑」维持。
2. **不做丢弃重注册**。`productTimeoutScan` / `ProductPayTimeoutConsumer` 已在票 03 删除，
   商品支付超时没有业务侧兜底；跑腿虽有 MQ 延迟消息兜底，但锁单/审核租约（复合 `bizId`）没有。
   丢弃会让订单/锁/租约卡死。**必须搬。**
3. **选一次性搬迁**：旧扫描 job 先停 → 新代码滚动上线 → 新扫描 job 也停 → 把
   `bs_timeout.timeout_task_*` 的 `WAITING/RETRYING/FAILED` 和新代码上线前残留的
   `timeout_register_outbox.PENDING` 搬进 `better_school.timeout_task_*` → 校验 → 开新扫描 job
   → 停旧中心 → 观察 → 清理。
4. **必须按新公式重算分表号**：旧 `slot = floorMod(hash(len(taskType)+":"+taskType+bizId),16)`，
   新 `slot = floorMod(hash(bizId),16)`。**直接 `INSERT ... SELECT` 按旧表号搬会写错表，
   新扫描器永远扫不到（静默丢失）**。工具已实现两个公式（单测夹具由 JDK 21 实测值钉住）。
5. **搬 `FAILED` 死信，不搬 `SUCCESS/CANCELED`**：前者要在单体死信管理面继续重发/取消；
   后者是历史，留在 `bs_timeout` 归档里。业务状态机才是业务事实源。
6. **目标已有同键行时保留目标行**（新代码的注册是新一代事实源）。搬迁只补缺、不覆盖；
   重复执行安全。
7. **旧中心只在验收后清理**：先停 job、停进程、摘 Nacos、留 7 天回滚窗口；`bs_timeout` 先 dump
   + restore drill，留 30 天再 DROP。`backend/timeout-center/` 的删除归票 09，且必须在验收之后。
8. **窗口内旧回调暂停**：从停旧 job 到开新 job 之间，旧的支付/锁单/租约超时任务不触发。
   尽量把窗口压到 30 分钟内；业务状态机有守卫，延迟触发不会把已支付订单再关一次。

## 1. 搬迁映射（工具替你做的事）

| 旧位置 | 新位置 | 过滤/规则 |
| --- | --- | --- |
| `bs_timeout.timeout_task_00..15` | `better_school.timeout_task_{newSlot:02d}` | 只搬 `WAITING` / `RETRYING` / `FAILED`；`slot` 按 `hash(biz_id) % 16` 重算 |
| `better_school.timeout_register_outbox`（`PENDING` 残留） | `better_school.timeout_task_{newSlot:02d}` | 新代码没有发送器了；不搬就永远发不出去。`action_time` 原样，`next_fire_time = action_time`，`retry_count = 0` |
| 旧行 `callback_group` | 丢弃 | 新表没有这一列；进程内分发按 `taskType` 走 |
| `SUCCESS` / `CANCELED` | 不搬 | 历史留在 `bs_timeout` 归档 |

冲突规则：

- 目标已有同键行（新代码已经写过）→ **保留目标行，跳过旧行**；报告里逐条记录。
- 旧中心行与 outbox 同键时，按旧中心状态 + `action_time` 判断：
  - 旧中心在途（`WAITING/RETRYING`）→ 跳过 outbox（在途同键不改期）；
  - 旧中心终态、outbox 的 `action_time` **更晚** → 这是 outbox 复位后的**新一轮注册**
    （旧中心本会复活终态行），按 outbox 搬；
  - 旧中心终态、outbox 不更新 → 视为发送失败的残留，跳过。
- 源库出现重复 `(task_type, biz_id)` → 工具报错退出，先人工核对，不自动选一行。
- 前向搬迁是**只增不改**的；失败/中断后直接重跑，不会覆盖已有目标行。

工具的前置保护（不满足会直接退出）：

- 源/目标各有 16 张分表；目标表不存在时提示先执行 `better-school/sql/schema.sql`。
- 旧扫描 job `timeoutTaskScanJob` 必须 `trigger_status = 0`；新扫描 job `timeoutTaskScan` 也必须 `0`
  （校验期间不能让新代码把刚搬进去的行结算掉，否则校验读到的是漂移后的状态）。
- 目标库已有行时，必须显式 `--allow-existing-target`（滚动上线后这是正常状态）。
- `--force` 只用于「确认过风险」的越过（例如 xxl 库读不到、或你确实要并发扫描）；
  正常上线不要用。

## 2. 前置准备（T-1 天）

### 2.1 版本与工件

- [ ] `better-school` 新版本已构建、制品可部署；`marketing` 不受影响。
- [ ] 旧 `timeout-center` 的 jar/镜像、启动命令、旧配置**保留至少 30 天**，回滚要用。
- [ ] 确认 `backend/timeout-center/` 源码尚未按票 09 删除（删除必须在验收之后）。
- [ ] 确认新版本启动日志里有 `ShardingSelfCheck` 通过、`timeoutTaskScan` handler 注册成功。

### 2.2 数据库

- [ ] 对生产 `better_school` 执行 `better-school/sql/schema.sql`（`CREATE TABLE IF NOT EXISTS`，
      老代码不受影响）。确认 16 张分表存在：

```sql
SELECT COUNT(*) AS tables_ok
FROM information_schema.tables
WHERE table_schema = 'better_school'
  AND table_name REGEXP '^timeout_task_(0[0-9]|1[0-5])$';
-- 期望 16
```

- [ ] 确认 `bs_timeout` 与 `better_school` 在**同一个 MySQL 实例**上（同一连接能 schema 限定访问）。
      若生产不是同实例，先把 `bs_timeout` dump 恢复到业务实例的临时 schema，再执行工具。
- [ ] 备份（两个都要）：

```bash
mysqldump --single-transaction --routines --triggers bs_timeout \
  > bs_timeout-pre-cutover-$(date +%Y%m%d%H%M).sql
mysqldump --single-transaction better_school timeout_register_outbox \
  > outbox-pre-cutover-$(date +%Y%m%d%H%M).sql
sha256sum bs_timeout-pre-cutover-*.sql outbox-pre-cutover-*.sql
```

- [ ] 记录源库各状态量（后面用来对账）：

```sql
SELECT SUM(status = 'WAITING')  AS waiting,
       SUM(status = 'RETRYING') AS retrying,
       SUM(status = 'FAILED')   AS failed,
       SUM(status = 'SUCCESS')  AS success,
       SUM(status = 'CANCELED') AS canceled
FROM (
    SELECT status FROM bs_timeout.timeout_task_00
    UNION ALL SELECT status FROM bs_timeout.timeout_task_01
    -- ... 一直 UNION 到 timeout_task_15
) t;
```

- [ ] 记录 outbox 残留：

```sql
-- 列名注意：该表是 type/state/create_time/update_time，不是分表的 task_type/status/created_at/updated_at
SELECT state, COUNT(*) FROM better_school.timeout_register_outbox GROUP BY state;
```

### 2.3 xxl-job / 端口 / Nacos

- [ ] 记录旧 job 与新 job 的 `id`、`job_group`、`trigger_status`：

```sql
SELECT id, job_group, executor_handler, trigger_status, schedule_conf, executor_route_strategy
FROM xxl_job.xxl_job_info
WHERE executor_handler IN ('timeoutTaskScanJob', 'timeoutTaskScan');
SELECT id, app_name, title, address_list, update_time
FROM xxl_job.xxl_job_group
WHERE app_name IN ('timeout-center-executor', 'better-school-executor');
```

- [ ] 确认新 job seed 已执行（`timeoutTaskScan` 行存在）。没有就先执行
      `better-school/sql/xxl-job-tasks.sql`（幂等）；否则 Phase 3 没有 job 可开。

- [ ] 确认旧中心端口：HTTP `8083`、Dubbo `50054`、xxl executor `9996`。
- [ ] 确认新单体端口：HTTP `8080`、Dubbo `50051`、xxl executor `9999`（与旧中心不冲突）。
- [ ] 确认 Nacos 上旧服务名 `timeout-center` 与 `ITimeoutTaskRegister` 的 Provider 清单（回滚时核对）。
- [ ] 通知：窗口内支付超时关单、跑腿锁单释放、审核租约回收会延迟，不丢单。

## 3. 切换步骤

所有 SQL 用同一个高权限连接执行；`mysql` 参数按环境替换。下文用
`$MYSQL` 代表 `mysql -h <host> -P <port> -u <user> -p`。

### Phase 0：停旧扫描（T0）

```sql
UPDATE xxl_job.xxl_job_info
SET trigger_status = 0
WHERE executor_handler = 'timeoutTaskScanJob';
```

- [ ] 等 **60 秒**（6 个 10s 周期），确认 xxl-job admin 里旧任务无「运行中」实例；
      旧中心日志不再出现 `timeoutTaskScanJob` 的扫描完成行。
- [ ] 此后旧中心只保留注册 Provider（给还没下线的旧业务实例发注册），不再触发任何回调。
- [ ] 记录 T0。

**回滚点 0**：把 `trigger_status` 改回 `1` 即恢复；无数据变化。

### Phase 1：滚动上线新版本（T0 → T1）

- [ ] 按正常发布流程滚动 `better-school`。新实例的效果：
      - 新下单/抢单/领单在业务事务内直插 `better_school.timeout_task_*`；
      - 本进程的 `timeoutTaskScan` 此时仍启用，只扫到新代码自己写入的新行（旧在途行还在
        `bs_timeout`，它看不见）；Phase 2 开始前必须停掉它，再做前向搬迁与校验；
      - 旧实例仍写 `timeout_register_outbox` 并调旧中心，旧中心扫描已停，所以只积压不触发。
- [ ] 等所有实例都切到新版本（发布平台确认，不能有旧实例继续服务）。
- [ ] 等 outbox 不再增长（老实例停完后没有新写入；通常在 30s 内稳定）：

```sql
SELECT COUNT(*) AS pending FROM better_school.timeout_register_outbox WHERE state = 'PENDING';
-- 连续两次（间隔 30s）相同即可；>0 也没关系，搬迁会一起处理
```

- [ ] 记录 T1。

**回滚点 1**：把业务版本回退到旧代码；旧中心仍在，数据没动，恢复旧 job 即可。
如果部分新实例已经写入过目标表，先按第 5 节 `reverse` 把这些行搬回旧中心，再回退。

### Phase 2：停新扫描 + 前向搬迁（T1 → T2）

先停新扫描（前向搬迁/校验期间不允许结算目标行）：

```sql
UPDATE xxl_job.xxl_job_info
SET trigger_status = 0
WHERE executor_handler = 'timeoutTaskScan';
```

- [ ] 等 10s，确认新 job 无运行中实例。如果 `timeoutTaskScan` 行还不存在（seed 未执行），跳过本步。
- [ ] 预检 + dry-run（不写库）：

```bash
python backend/scripts/timeout-cutover-migrate.py forward \
  --host <host> --port <port> --user <user> \
  --source-schema bs_timeout --target-schema better_school --xxl-schema xxl_job \
  --allow-existing-target --dry-run \
  --report /tmp/timeout-cutover-forward-dry.json
```

看三件事：

1. `[forward] 源 N 行 ...` 与第 2.2 节记录的非终态量对得上（`WAITING+RETRYING+FAILED`）；
2. `outbox PENDING` 与第 2.2 节记录一致；
3. `[error]` 没有；`[warn]` 里的目标已有行数能解释（新代码上线后下的单）。

- [ ] 正式搬迁：

```bash
python backend/scripts/timeout-cutover-migrate.py forward \
  --host <host> --port <port> --user <user> \
  --source-schema bs_timeout --target-schema better_school --xxl-schema xxl_job \
  --allow-existing-target \
  --report /tmp/timeout-cutover-forward.json
```

- [ ] 退出码必须为 `0`，报告里 `"verification": {"ok": true}`。工具会校验：
      - 每条源行/outbox 行都在目标库有同键行；
      - 本次插入的行状态、`action_time` 与源一致；
      - **目标库每一行的物理表号都等于新公式槽位**（这是「扫描器不漏行」的硬约束）。

如果失败：

- 旧中心数据还在，**不要继续**；
- 按报告修数据后直接重跑（只增不改，安全）；
- 若报「源库存在重复键」或「目标槽位错误」，先人工核对，不要用 `--force` 掩盖。
- 若报 `Duplicate entry ... for key 'uk_type_biz'`：这是「读取目标的快照之后，新代码刚好写入同键」
  的窄窗口。整个事务已回滚，**不要手工删目标行**，直接重跑即可（新目标行会被判为「目标已有」而跳过）。

- [ ] 独立抽查：

```sql
-- 抽几条源行，确认目标同键存在且在新公式表里
SELECT task_type, biz_id, status, action_time
FROM better_school.timeout_task_00
WHERE biz_id IN ('<源 bizId 1>', '<源 bizId 2>');

-- 目标死信是否包含旧 FAILED
SELECT COUNT(*) AS failed FROM (
    SELECT status FROM better_school.timeout_task_00
    UNION ALL SELECT status FROM better_school.timeout_task_01
    -- ... 到 _15
) t WHERE status = 'FAILED';
```

- [ ] 记录 T2。

**回滚点 2**：目标表只增不改，旧中心数据完整。停新 job、回退业务版本、恢复旧 job
即可；新代码期间产生的目标行用第 5 节 `reverse` 搬回。

### Phase 3：开新扫描，验证回调（T2 → T3）

```sql
UPDATE xxl_job.xxl_job_info
SET trigger_status = 1
WHERE executor_handler = 'timeoutTaskScan';
```

- [ ] 观察 2–3 个 10s 周期，确认 `timeoutTaskScan` 执行成功、日志出现：
      `扫描完成：槽位 N 个、批次 M、任务 K 条（成功 x / 重试 y / 进死信 z）`。
- [ ] 已经到期的旧行应在下一个周期被回调：业务状态应从待支付/锁定/待领变成目标终态；
      已支付/已取消的陈旧行按幂等成功落 `SUCCESS`。
- [ ] 打开管理面 `GET /api/admin/timeout-tasks/dead-letters`（ADMIN），确认旧 `FAILED` 能看到。
- [ ] 记录 T3。

**回滚点 3**：此时旧中心仍完整、只停扫描。回退步骤同回滚点 2。

### Phase 4：停旧中心（T3 → T4）

- [ ] 优雅停掉旧中心进程（不要先删库/删 job）。
- [ ] 确认端口释放：

```bash
ss -lntp | grep -E ':(8083|50054|9996)\b'   # 期望无输出
```

- [ ] 确认 Nacos 上无 `timeout-center` 实例（Nacos 控制台或
      `GET /nacos/v1/ns/instance/list?serviceName=timeout-center`）；如有残留实例，手工下线。
- [ ] 确认旧 job `trigger_status = 0`。
- [ ] 记录 T4。

**回滚点 4**：重新启动旧中心 + 第 5 节 `reverse` + 恢复旧 job。

### Phase 5：观察期（T4 + 24h / 7d）

- [ ] 24h 内每 10s 扫描无异常；`[超时中心告警]` 日志为 0 或可解释。
- [ ] `WAITING/RETRYING` 数量回落到正常水位；没有持续增长的单一 `taskType`。
- [ ] 死信数量不异常增长；旧 `FAILED` 搬过来的行可按需重发/取消。
- [ ] 四个 `taskType` 各抽一单做业务验收：
      `PRODUCT_ORDER_PAY_TIMEOUT` / `ERRAND_PAY_TIMEOUT` / `ERRAND_LOCK_TIMEOUT` / `REVIEW_LEASE_TIMEOUT`。
- [ ] 旧库 `bs_timeout` 已无新写入（`MAX(updated_at)` 停在 T4 之前）。
- [ ] 7 天后验收通过，进入清理。

## 4. 清理口径（验收后）

### 4.1 xxl-job

先归档（留档到发布记录/工单），再删。**先删 `xxl_job_info`，再删 `xxl_job_group`**：

```sql
-- 归档
SELECT * FROM xxl_job.xxl_job_info WHERE executor_handler = 'timeoutTaskScanJob'\G
SELECT * FROM xxl_job.xxl_job_group WHERE app_name = 'timeout-center-executor'\G

-- 删除
DELETE FROM xxl_job.xxl_job_info WHERE executor_handler = 'timeoutTaskScanJob';
DELETE FROM xxl_job.xxl_job_group WHERE app_name = 'timeout-center-executor';
```

保留：

- `xxl_job_info` 的 `timeoutTaskScan`（新扫描 job，启用）；
- `xxl_job_group` 的 `better-school-executor`（新执行器）。

`xxl_job_log` 旧 job 的历史日志可保留到日志保留期结束；不要为了删 job 顺手清全表。

### 4.2 Nacos

- [ ] 旧中心进程停止后应自动摘除；检查 `timeout-center` 应用与
      `providers:com.betterschool.timeout.api.task.ITimeoutTaskRegister` 的残留实例。
- [ ] 手工删除残留实例（控制台或
      `DELETE /nacos/v1/ns/instance?serviceName=...&ip=...&port=...`）。
- [ ] 旧中心的 Nacos 配置（dataId/group）确认无其他服务引用后再删除；不确定就留档。

### 4.3 端口 / 监控 / 网关

- [ ] 防火墙/安全组关闭 `8083`、`50054`、`9996` 的入站（旧中心已停）。
- [ ] 从监控、告警、日志采集、负载均衡/反代中移除 `timeout-center`。
- [ ] 确认新单体 `8080` / `50051` / `9999` 的监控已就位。

### 4.4 `bs_timeout` 库：留档 30 天再 DROP

- [ ] 停旧中心后再做一次最终 dump（旧库已无写入）：

```bash
mysqldump --single-transaction --routines --triggers bs_timeout \
  > bs_timeout-final-$(date +%Y%m%d).sql
sha256sum bs_timeout-final-*.sql
```

- [ ] 做一次恢复演练（证明 dump 可用）：

```bash
mysql -e 'DROP DATABASE IF EXISTS bs_timeout_restore; CREATE DATABASE bs_timeout_restore'
mysql bs_timeout_restore < bs_timeout-final-YYYYMMDD.sql
mysql -e "SELECT COUNT(*) FROM bs_timeout_restore.timeout_task_00"
```

- [ ] dump 存到备份仓库/工单附件，保留 **30 天**。
- [ ] 30 天后、且验收无回滚需求，执行：

```sql
DROP DATABASE IF EXISTS bs_timeout;
DROP DATABASE IF EXISTS bs_timeout_test;   -- 测试/预发环境
```

- [ ] 同步票 09 的 `dev-stack.ps1` / `test-env.sh` 清理项（不再建 `bs_timeout`）。

### 4.5 `timeout_register_outbox`

票 03 已把它从 `better-school/sql/schema.sql` 删除，但生产库里的实体表还在。验收后：

```sql
-- 先归档
SELECT * FROM better_school.timeout_register_outbox;   -- 或 mysqldump 单表
-- 再删
DROP TABLE better_school.timeout_register_outbox;
```

确认没有其他代码/报表引用后再删；`grep -r timeout_register_outbox` 应只剩历史 `.scratch/**`、
本清单与迁移工具（后两者是历史追溯用的文档/脚本，不是运行时引用）。

### 4.6 工程与文档

- [ ] 票 09：删除 `backend/timeout-center/` 整目录、改 `backend/CLAUDE.md` 的「三个工程」为两个、
      改写 `backend/docs/architecture/timeout-center.md`、补 ADR/词条。
- [ ] 本清单与工具保留在 `backend/docs/ops/`、`backend/scripts/`（历史追溯用；不属于运行时代码）。
- [ ] 确认根 `pom.xml` 无 `timeout-center-api` 残留、本地仓库说明已更新。

## 5. 回滚

### 5.1 阶段矩阵

| 当前阶段 | 回滚动作 | 数据动作 |
| --- | --- | --- |
| Phase 0（旧 job 已停，未发版） | 旧 job `trigger_status=1` | 无 |
| Phase 1（发版中/刚发完，未搬迁） | 停新 job、停新实例；回退业务版本；最后恢复旧 job `1` | 新实例若写过目标表，先 `reverse`（旧 job 必须仍为 `0`） |
| Phase 2（前向搬迁已跑/校验失败） | 停新 job、停新实例；回退业务版本；最后恢复旧 job `1` | 目标表只增不改，旧中心数据完整；新代码期间产生的目标行按需 `reverse` |
| Phase 3（新 job 已开） | 停新 job、停新实例；回退业务版本；最后恢复旧 job `1` | 先 `reverse` 再恢复旧 job，避免新代码期间的行丢 |
| Phase 4（旧中心已停） | 起旧中心；回退业务版本 | 先 `reverse`（旧中心在停着时插入），再起旧中心、恢复旧 job |
| Phase 5/清理后 | 从 `bs_timeout` dump 恢复；重新部署旧中心工件 | 先恢复 dump，再 `reverse` 新表在途/死信；最后恢复旧 job |

### 5.2 `reverse` 命令（业务库 → 旧中心）

前提：

1. 新扫描 job `timeoutTaskScan` 置 `0`；
2. 旧扫描 job `timeoutTaskScanJob` 也置 `0`（回滚期间它会把刚写回旧库的行结算掉）；
3. 停掉所有新版本业务实例（不再写目标表、不再扫描）；
4. 旧中心可以还没起；工具只写 `bs_timeout`，不依赖旧进程。

```bash
# dry-run
python backend/scripts/timeout-cutover-migrate.py reverse \
  --host <host> --port <port> --user <user> \
  --source-schema bs_timeout --target-schema better_school --xxl-schema xxl_job \
  --callback-group better-school --dry-run \
  --report /tmp/timeout-cutover-reverse-dry.json

# 正式
python backend/scripts/timeout-cutover-migrate.py reverse \
  --host <host> --port <port> --user <user> \
  --source-schema bs_timeout --target-schema better_school --xxl-schema xxl_job \
  --callback-group better-school \
  --report /tmp/timeout-cutover-reverse.json
```

`reverse` 的规则：

- 目标 `WAITING/RETRYING` → 按**旧公式**写回 `bs_timeout`；
- 旧中心已有同键在途行 → 跳过（不重复触发）；
- 旧中心同键是终态、目标是在途 → 复活旧行（状态/`action_time`/`next_fire_time`/`retry_count` 以目标为准）；
- 目标已经 `SUCCESS/CANCELED`、旧中心还是 `WAITING/RETRYING` → 把旧行置 `CANCELED`
  （业务动作已经发生，避免回滚后重复回调；不想要这个行为加 `--keep-stale-old`）；
- 目标 `FAILED`：
  - 旧中心没有同键行 → 插入死信行（保留新中心的人工重发能力）；
  - 旧中心同键死信 → 保留旧行；
  - 旧中心同键在途 → 保留旧在途继续重试，不拿死信覆盖；
  - 旧中心同键已 `SUCCESS/CANCELED` → **保留旧终态**，不把新死信覆盖回去
    （业务动作在旧中心已经结算；报告里逐条记录）。

`reverse` 完成后：

1. 部署旧版业务代码（恢复 Dubbo 回调 Provider）；
2. 启动旧中心（如果已停）；
3. 旧 job `trigger_status=1`；
4. 观察旧 job 扫到搬回的行并回调成功。

### 5.3 回滚后的清理

- 新代码在目标表里留下的行不用删（旧代码不读它）；确认旧中心接管后，按下一轮改造重新评估。
- 不要把 `reverse` 与 `forward` 同时跑；两个方向都要求对方扫描 job 停止。
- 若已经执行了 4.4 的 DROP，先恢复 dump 再 `reverse`。

## 6. 验收 / 观察指标

| 指标 | 正常 | 异常处理 |
| --- | --- | --- |
| `timeoutTaskScan` 10s 执行成功 | 连续成功 | 看日志 `[超时中心告警]`；旧 job 不得为 1 |
| `WAITING + RETRYING` 总量 | 与改造前水位相当、无持续增长 | 按 `taskType` 分解，查业务注册点 |
| `FAILED` 数量 | 不异常增长；旧死信可见 | 管理面重发/取消 |
| 回调并发 / Hikari 活跃连接 | ≤ 4 / ≤ 4（池 10 留 ≥6） | 调 `timeout.scan.parallelism`，不要动池 |
| `bs_timeout` 写入 | `MAX(updated_at)` 停在 T4 前 | 有写入 = 旧代码/旧 job 未停干净 |
| 旧端口 8083/50054/9996 | 无监听 | 有监听 = 旧进程未停 |
| Nacos `timeout-center` | 无实例 | 手工下线 |

## 7. 已知风险与对策

| 风险 | 对策 |
| --- | --- |
| 窗口内旧回调暂停，支付/锁单/租约超时延迟 | 窗口压到 30min 内；业务状态机幂等守卫；延迟触发不会重复关单/释放 |
| 分表号用错（按旧表号搬） | 工具强制新公式；校验目标每行槽位；禁止 `INSERT ... SELECT` 直接搬 |
| outbox PENDING 残留被遗忘 | 工具默认搬；预检报告 PENDING 数；验收后 DROP 旧表 |
| 搬迁时新代码刚好写入同键 | 读取快照后撞 `uk_type_biz` 会整批回滚；不要手工删目标行，直接重跑（目标已有键会被跳过） |
| 新扫描器在校验期间结算目标行 | 前向搬迁前停 `timeoutTaskScan`；工具前置检查；校验通过再开 |
| 旧中心停不干净（job/进程/端口） | Phase 0 停 job、Phase 4 停进程并验端口/Nacos；旧库 `MAX(updated_at)` 再确认 |
| 回滚漏掉新代码期间产生的行 | `reverse` 覆盖目标 `WAITING/RETRYING`；目标 `FAILED` 无旧行时插死信，有旧行时保留旧中心事实 |
| `bs_timeout` 未归档就 DROP | 先 dump + restore drill + 30 天保留；票 09 才删工程 |
| 生产不是同一 MySQL 实例 | 工具要求同一连接；不同实例先把 `bs_timeout` 恢复到业务实例临时 schema |
| 误用 `--force` | 只在确认 job 状态/并发风险后使用；报告会记录 warning |

## 8. 工具速查

```bash
python backend/scripts/timeout-cutover-migrate.py --help
python backend/scripts/timeout-cutover-migrate.py forward --help
python backend/scripts/timeout-cutover-migrate.py reverse --help

# 离线单测（不需要 MySQL）
python backend/scripts/test_timeout_cutover_migrate.py -v
```

连接参数优先取环境变量：`MYSQL_BIN` / `MYSQL_HOST` / `MYSQL_TCP_PORT` / `MYSQL_USER` / `MYSQL_PWD`，
也可以用 `--mysql/--host/--port/--user/--password` 覆盖。脚本只依赖 `mysql` 客户端，
不额外安装 Python 驱动。

这是**数据搬迁**工具，不是 DDL 迁移：`schema.sql` 仍是唯一 DDL 来源（`backend/CLAUDE.md` §10）。

**退出码**：

- `0`：成功（dry-run 也返回 0，但报告里 `dry_run=true`、`verification` 未执行）；
- `2`：预检失败或执行失败——没有写库，或写入事务已整体回滚；
- `3`：**写入已 COMMIT，但落库/槽位校验失败**。不要立刻重跑掩盖问题：先看报告
  `verification.errors`，人工核对目标表；修好后重跑是安全的（只增不改），但要先确认
  失败原因不是「新扫描器在搬迁期间结算了行」。

报告字段：

- `counts`：源/目标各状态量、计划/实际插入、跳过；
- `conflicts`：逐条跳过原因（目标已有键、outbox 已在旧中心等）；
- `verification.ok` / `errors`：落库与槽位校验结果；
- `warnings`：job 状态、目标已有行、outbox 存在等操作提醒。

## 9. 口径对照（为什么不是另外两个候选）

- **(a) 双跑**：需要旧中心继续回调业务方。票 05 已删除业务侧
  `TimeoutTaskCallbackDubboService`、`ITimeoutTaskCallback` DTO 与 `timeout-center-api` 依赖，
  旧中心的动态 `ReferenceConfig` 找不到 Provider；要继续双跑就得把刚删掉的 Dubbo 回调边加回来，
  同时保留三工程/两套扫描，和「一次性下线」的方向相反。**否决。**
- **(c) 丢弃重注册**：商品支付超时的 `productTimeoutScan` / `ProductPayTimeoutConsumer` 已在票 03 删除，
  没有业务侧兜底；跑腿锁单/审核租约的复合 `bizId` 也没有可重放的业务事件来源。**会卡死，否决。**
- **(b) 一次性搬迁**：就是本清单。代价是一次短暂的旧回调暂停和一次数据搬迁；换来旧中心可以真正停掉、
  不留双跑、不恢复已删的回调边。

## 10. 与票 09 的边界

- 本清单负责：**切换顺序、数据搬迁工具、回滚、清理口径**。
- 票 09 负责：删 `backend/timeout-center/`、改写 `backend/docs/architecture/timeout-center.md`、
  改 `backend/CLAUDE.md` 工程数、补 ADR/词条、`dev-stack.ps1` / `test-env.sh` 的 `bs_timeout` 清理。
- 顺序：**先按本清单验收，再开工票 09**；票 09 删除工程后，旧中心回滚工件只能靠已保留的 jar/镜像。
