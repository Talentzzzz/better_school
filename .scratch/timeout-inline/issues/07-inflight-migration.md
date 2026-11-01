# 07 存量在途任务收尾与切换顺序（决策 + 执行）

Type: grilling
Status: resolved
Blocked by: 03, 04, 05

## Question

改造上线那一刻，`bs_timeout.timeout_task_*` 里还有在途行（`WAITING`/`RETRYING`）——它们是**旧中心**的记录，新代码根本不会去扫它们。怎么收尾？候选：

- **(a) 双跑一个窗口**：新代码上线后旧中心继续跑到在途行清空（最长 ≈ 15min 支付超时 + 重试窗 10h），期间两个中心并存；旧中心回调的业务代码未变（四个 `handleXxx` 不动），所以安全。代价与 §10「一次性下线、不留双跑」的旧口径冲突。
- **(b) 一次性搬迁**：切换前用一个一次性 job 把在途行按 `taskType` 重新注册进业务库的 `timeout_task_*`（`bizId`/`action_time` 原样搬），然后停旧中心。搬迁本身需要处理幂等（目标表可能已有同键行）与「搬迁期间到点」的竞态。
- **(c) 丢弃重注册**：不搬，靠业务侧兜底（商品/跑腿的支付超时在业务侧还有没有兜底，要实测确认——`productTimeoutScan` 本期要删）。

还要一并拍**切换顺序与清理**：旧中心停写 → 停 job → 摘 Nacos 注册 → 释放 8083/50054/9996 → `timeout-center-executor` 从 xxl-job admin 下线（含 `xxl_job_info`/`xxl_job_group` 的清理口径）→ `bs_timeout` 库留档还是 DROP。

## 依据

现网在途量级：spec §5.6 记「在途峰值数千行」；旧中心的 job seed 在 `timeout-center/sql/xxl-job-tasks.sql:16-34`；四个 taskType 的业务动作都在 better-school 内（本期不删），所以 (a) 的兼容性成立。

## Answer

**已落地（2026-10-17）。选 (b) 一次性搬迁。**

**为什么 (a)/(c) 都不行**：

- **(a) 双跑否决**：双跑要求旧中心继续回调业务方，但票 05 已删除业务侧
  `TimeoutTaskCallbackDubboService`、`ITimeoutTaskCallback` DTO 与 `timeout-center-api` 依赖；
  旧中心的动态 `ReferenceConfig` 找不到 Provider，窗口内的回调会全部失败并进死信。
  要继续双跑就得把刚删掉的 Dubbo 回调边加回来，与「一次性下线」方向相反。
- **(c) 丢弃重注册否决**：`productTimeoutScan` / `ProductPayTimeoutConsumer` 已在票 03 删除，
  商品支付超时没有业务侧兜底；跑腿锁单/审核租约的复合 `bizId` 也没有可重放的业务事件来源。
  丢弃会让订单/锁/租约卡死。

**搬迁范围与规则**：

- 旧 `bs_timeout.timeout_task_00..15` 只搬 `WAITING` / `RETRYING` / `FAILED`；
  `SUCCESS` / `CANCELED` 是历史，留在 `bs_timeout` 归档。`FAILED` 必须搬，否则单体死信管理面看不到。
- 一并搬 `better_school.timeout_register_outbox` 的 `PENDING` 残留（票 03 删了发送器，不搬就永远发不出去）；
  新表没有 `callback_group`，该列丢弃。
- **分表号必须按新公式重算**：旧 `slot = floorMod(hash(len(taskType)+":"+taskType+bizId), 16)`，
  新 `slot = floorMod(hash(bizId), 16)`。按旧表号 `INSERT ... SELECT` 会把行写进错误分表，
  新扫描器永远扫不到（静默丢失）。工具两个公式都实现，槽位夹具由 JDK 21 实测钉住。
- 目标已有同键行时保留目标行（新代码是新一代事实源），只补缺不覆盖；outbox 同键时按旧中心状态
  与 `action_time` 区分：在途 → 跳过；终态且 outbox 更晚 → 新一轮注册，搬；终态且 outbox 不更新 →
  残留，跳过。源库重复 `(task_type, biz_id)` 直接报错；重复执行幂等。
- 前向搬迁要求旧 job `timeoutTaskScanJob` 与新 job `timeoutTaskScan` 都为 `0`（否则校验期间行被结算，
  状态漂移）；目标非空需显式 `--allow-existing-target`。校验不仅查源键都在目标，还查目标每一行
  物理表号都等于新公式槽位。

**切换顺序（展开为可照做清单 `backend/docs/ops/timeout-inline-cutover.md`）**：
Phase 0 停旧扫描 job → Phase 1 滚动上线新代码（新 job 仍启用，只扫新代码写入的行）
→ Phase 2 停新扫描 + `forward` + 校验
→ Phase 3 开新扫描、验四个 taskType 回调与死信可见 → Phase 4 停旧中心进程、摘 Nacos、释放 8083/50054/9996
→ Phase 5 观察 24h/7d → 清理。窗口内旧超时回调暂停（尽量压到 30 分钟内），业务状态机有幂等守卫。

**回滚**：分阶段矩阵见清单 §5。数据方向用同一工具的 `reverse`：目标 `WAITING/RETRYING`
按旧公式写回 `bs_timeout`；旧中心已有在途行跳过；旧终态行按目标复活；目标已 `SUCCESS/CANCELED`
而旧行仍在途时把旧行置 `CANCELED`（`--keep-stale-old` 可关）。目标 `FAILED` 只在旧中心没有同键行时
插入；旧中心在途/死信/已结算时保留旧行（业务已结算的不拿新死信覆盖），报告逐条记录。
reverse 前必须停新 job、停新版本实例，并确认旧 job 也已停（工具会检查）。

**清理口径**：旧 job 先归档再删，**先 `xxl_job_info` 后 `xxl_job_group`**（`timeout-center-executor`），
保留新的 `timeoutTaskScan` 与 `better-school-executor`；Nacos 残留实例手工下线；端口入站关闭；
`bs_timeout` 停写后最终 dump + restore drill，**留档 30 天再 DROP**（含测试库）；
`timeout_register_outbox` 归档后 DROP。`backend/timeout-center/` 整目录删除归票 09，且必须在验收之后。

**产出**：

- `backend/scripts/timeout-cutover-migrate.py`：`forward` / `reverse`、dry-run、前置检查、单事务写入、
  落库 + 槽位校验、JSON 报告；只依赖 `mysql` CLI。
- `backend/scripts/test_timeout_cutover_migrate.py`：39 个离线单测（Java hashCode/UTF-16 夹具、
  forward/reverse 计划、冲突规则、SQL 生成、outbox 真实列名回归、outbox 新旧轮次判定、job 前置守卫、
  事务包裹）。
- `backend/docs/ops/timeout-inline-cutover.md`：上线/回滚清单（决策、映射、Phase 0–5、清理、回滚矩阵、
  风险表、工具速查）；`backend/docs/README.md` 与 arch doc §0 已加索引/指路。

**验收证据（本机 3307 scratch MySQL，全新 `cut2_src/cut2_dst/cut2_xxl` seed）**：

- 旧 job=1 → `forward` 预检退出码 2；新 job=1 → 退出码 2；`reverse` 同样检查旧/新 job。
- dry-run：源 5 行（WAITING/RETRYING/FAILED）、outbox PENDING 3 行、计划插入 5、跳过 3。
- 正式 forward：5 插入、3 跳过，`verification.ok=true`；重跑 0 插入、8 跳过。
- reverse：2 插入（outbox 5005 + 新代码 8008）、1 复活、1 取消陈旧旧行、3 跳过，`verification.ok=true`；
  逐键核对旧库状态与新/旧公式落表；终态键的 outbox 残留没有被复活。
- 人为把目标行放错分表 → `forward` 校验退出码 3 并报「槽位错误」。
- 单测 39/39 通过。

**执行中发现并纠正的缺陷**：

- 工具/清单首版把 `timeout_register_outbox` 的列名写成
  `task_type/status/created_at/updated_at`，真实 DDL（票 03 删表前 `schema.sql` + mapper XML）是
  `type/state/create_time/update_time`。临时 seed 恰好照抄了错误列名，上轮 E2E 没暴露；本轮按真实 DDL
  重新 seed 后修复并加回归测试（否则 `forward` 在真实生产库会直接 `Unknown column`）。
- outbox 冲突规则原先只比对「待迁移的非终态行」：旧中心同键已是 `SUCCESS/CANCELED` 时，
  `PENDING` 残留会被误当成「中心没落库」重新注册。现在按「旧中心在途 / 终态且 outbox 更晚 /
  终态且 outbox 不更新」三分：前两者分别跳过与搬，后者按残留跳过；E2E 覆盖「陈旧终态键不被复活」，
  单测覆盖「终态旧行 + 更晚 action_time = 新一轮注册」。
- xxl 库/表读不到时原实现只警告就放行，且不检查新 job seed 是否存在；已改为默认拒绝（需 `--force`），
  新 job 缺失时明确提示先执行 `better-school/sql/xxl-job-tasks.sql`；`reverse` 也补了旧 job 必须停的守卫。
