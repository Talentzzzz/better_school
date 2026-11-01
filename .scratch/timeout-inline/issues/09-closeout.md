# 09 收口：工程下线 + 文档/词条/ADR + 验证

Type: task
Status: resolved
Blocked by: 03, 04, 05

## What to build

1. **删工程**：`backend/timeout-center/` 整目录删除（含 `sql/`、`docs/`、两份 `sharding.yaml`）；`backend/CLAUDE.md` 的「三个独立 Maven project」改为两个。
2. **文档改写**（不是删了就算）：
   - `backend/docs/architecture/timeout-center.md` → 改成内嵌形态的架构文档（或拆进 `better-school/docs/`）；原 §4 的两条 Dubbo 边、§6 的池 24 来历、§8/§9 的注册与回调通道、§7 的管理面**逐节改写或删除**。
   - `better-school/docs/db/`、`docs/api/` 增补超时表与管理面；`sql.md`：表数口径（现「共 52 张 = 30+22」漏掉 `timeout_task`）+ timeout 段 + 删掉注册 outbox 这张表。
   - `dev-stack.ps1` / `scripts/test-env.sh` 里建 `bs_timeout` 的步骤；`docker-compose.yml` 注释里「三个 app 都 import 共享 dataId」的错说法（实测对 timeout-center 不成立）。
   - 清理 `pom.xml` 里 `timeout-center-api` 的依赖残留与本地仓库说明。
3. **词条**：`CONTEXT.md` 的「超时中心」词条要改（不再是独立微服务、不再有 outbox 注册通道/回调 group）；新增/调整的词条与票 08 协调，别两票改同一处。
4. **ADR**：写一篇（判断依据 = 难逆 + 反直觉 + 真权衡三条都成立）——「超时中心从独立微服务内嵌进业务服务：用 16 分表 + 分片广播换同事务注册」。编号顺延（现用到 0009）。
5. **验证**：
   - 两工程 `mvn -o clean package -DskipTests` 全 SUCCESS；集成测试全绿（带常驻栈）。
   - 端到端实跑一次：4 个 taskType 各走一遍「注册 → 到点 → 回调 → 状态落定」，其中至少一个走**退避重试**路径、一个走**死信**路径。
   - **反查**：全仓 grep `timeout-center` / `timeout_register_outbox` / `ITimeoutTaskCallback` / `callback_group` 零命中（历史 `.scratch/**` 除外）；`sql.md` 与两个 `schema.sql` 逐表逐列全等；`better_school` 表数 = 30 + 16（+ 管理面若有）。
   - 多实例形态验证：模拟 `shardIndex/shardTotal`（如 0/2、1/2）确认 16 张表不重叠认领、无重复回调。

## Answer

**已落地（2026-10-17）。票 09 收口完成，01–08 的决策未重开。**

**删工程与归档**
- `backend/timeout-center/` 整目录（七模块 + `sql/` + `docs/` + 两份 `sharding.yaml` + 旧测试）已删；旧架构文档 `backend/docs/architecture/timeout-center.md` 同步删除。
- 一次性迁移产物（runbook + 迁移脚本 + 39 个离线单测）从 `backend/` 移入 `.scratch/timeout-inline/archive/`：`timeout-inline-cutover.md`、`timeout-cutover-migrate.py`、`test_timeout_cutover_migrate.py`。原因：验收要求全仓（除 `.scratch/**`）旧 token 零命中，而这三份文件天生含旧表名/旧工程名。

**文档 / 词条 / ADR**
- 新建 `backend/docs/architecture/timeout.md`：内嵌形态、模块落位、两处规范例外、16 分表/槽位/ShardingSphere、同事务注册三分支、xxl 分片广播 + `parallelism=4`、分桶结算/退避/死信、进程内回调、管理面、多实例 0/2、1/2、接入步骤、测试、切换历史、坑速查。
- `sql.md` 删 §3 旧独立库整段；文首表数纠正为 **29 + 16 + 22 = 67**（票面「30 + 16」是票 03 删注册意图表之前的旧口径，第 30 张已随票 03 删除）；§1 timeout 段改为内嵌口径。
- `backend/CLAUDE.md`：三工程 → 两工程、适用范围；§1 增「超时注册显式例外」；§4 用「回调端口实现落 case 层」替换旧的注册边/outbox 例外。
- `backend/docs/README.md`、`architecture/marketing-services.md`、两工程 `docs/db|api`、`docker-compose.yml`、`dev-stack.ps1`、`test-env.sh`、`run-app.sh` 全部改为两库/两工程/两服务；`bs_timeout` 的建库、DDL、xxl seed 步骤删除；`dev-stack.ps1` 的 xxl 收口补删原独立工程的执行器组与 handler（`timeout-center-executor` / `timeoutTaskScanJob`）以及票 03 已删的 `productTimeoutScan` / `timeoutRegisterOutboxScan`（否则旧开发库会把新 `timeoutTaskScan` 绑在死组上）；`backend/docs/README.md` 顺带纠正「dev-stack 挂 JFR」的旧说法（JFR 在 `run-app.sh`）。
- `CONTEXT.md`：改写「超时任务」为内嵌形态、删除「注册 outbox」、新增「超时槽位」与「死信」；票 08 的「过期落账」未动。
- 新建 `docs/adr/0010-embedded-timeout.md`（难逆 / 反直觉 / 真权衡 + Alternatives + Consequences）。

**验证证据**
- 构建：`backend/better-school` 与 `backend/marketing` 均 `mvn -o clean package -DskipTests` **BUILD SUCCESS**。
- 全量集成测试：`cd backend/better-school; mvn -o test` → **328 tests, 0 failures, 0 errors, 0 skipped**（MySQL 容器未崩，无需环境事故记录）。
- 新增 / 修改用例：
  - `TimeoutTaskScanIntegrationTest` 补「两个实例各认领一半槽位」：先 `scan(0,2)` 再 `scan(1,2)`，两次合计 16 行、回调恰好 16 条且 bizId 无重复、全部 `SUCCESS`（该类 13 用例全绿）。
  - 新建 `TimeoutTaskEndToEndIntegrationTest`（6 用例）：四个 taskType 各走「注册 → 到点 → 真实扫描 → 真实进程内分发 → 业务状态落定」——商品支付超时（订单 `CANCELLED` / 商品 `ON_SALE`）、跑腿支付超时（`CANCELLED`）、跑腿锁单超时（`PUBLISHED` / `taker_id` null）、审核租约超时（`PENDING` / `recycle_count=1`）；退避（`RETRYING`、`retry_count=1`、`next_fire_time ≈ now+30s`）与死信（`FAILED`、`retry_count=16`、下一轮不再捞）。
- 反查：`git grep -n -E "timeout-center|timeout_register_outbox|callback_group|bs_timeout" -- ':!*.scratch*'` **零命中**；`ITimeoutTaskCallback\b` **零命中**（保留的 `ITimeoutTaskCallbackPort` 用词边界不误伤）；未跟踪文件同口径扫描 **零命中**。
- DDL 对照：用临时 Python 脚本（未入库）解析 `sql.md` 与两份 `schema.sql`，归一化空白/大小写/`IF NOT EXISTS` 后逐表逐列、索引、约束全等——`sql.md` §1 30 张（29 业务 + 逻辑 `timeout_task` 1 份）vs `better-school/schema.sql` 45 张（29 + 16，16 张分表互等）；§2 22 张 vs `marketing/schema.sql` 22 张。
- 表数：`better_school` / `better_school_test` 均 **45 = 29 业务 + 16 超时**；`bs_marketing` / `bs_marketing_test` 各 22（未落超时表）。
- 多实例：新 0/2、1/2 用例验收，另保留既有 1/3 用例。

**与票面的偏差**
1. 表数 **30 + 16 → 29 + 16**：第 30 张注册意图表已随票 03 删除（票面 charting 时点较早），本票按实际口径验收。
2. cutover 产物归档到 `.scratch/timeout-inline/archive/`，不留在 `backend/`（否则旧 token 无法零命中）。
3. `sql.md` §1 只写 1 张 timeout 分表是既有约定（16 张同构），不算缺失。
4. `bs-trigger` 的 `TimeoutTaskAdminController` 是票 06 的实现，但当时整目录未入库（提交 2460613 只改了其他文件）；本票收口时一并补入，否则提交后的代码树缺管理面、编译失败。
