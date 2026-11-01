# 05: 收尾与全项验收

**Spec:** `.scratch/service-restructure/spec.md` §2.6 其余归属、§3.4、§6 验收标准、§7

**What to build:** 三工程落位后的扫尾——测试环境脚本改写（6→4 库）、根级文档改写为三工程口径、logs 清理，然后按 §6 逐项验收，宣告重构完成。验收是本票的主体：编译/测试/启动/残留 grep/xxl/前端一个不漏。

**Blocked by:** 02, 03, 04（三工程全部落位后才能做）

**Status:** resolved

- [x] `scripts/test-env.sh`：`apply_marketing_init` 改为依次执行 activity/asset 两工程的 `init-{dev,test}.sql`（建库 6 → 4：`bs_activity`(+`_test`)、`bs_asset`(+`_test`)）；`reset-test-db` 同步；跑一遍确认 4 库建成
- [x] `backend/CLAUDE.md`：目录/路径示例改三工程口径，**规范条目本身不动**
- [x] 根 `docs/README.md`：索引改写为三工程口径（含「营销两工程按序在 `mvn install` 单体后构建」的构建顺序说明，§6-1）
- [x] `docs/architecture/marketing-services.md`：改写为两服务口径（留根单份，§4-8）
- [x] `backend/logs/` 删除（零跟踪）；`.idea/` 不迁移保持忽略
- [x] 结构验收（§6-7）：`backend/` 根只剩 §1 目录树所列项（`sql/` 只剩 `xxl-job.sql`）；`git status` 无意外未跟踪产物
- [x] §6-1/2：三工程各自 `mvn compile` + 全量 `mvn test` 通过（顺序：先单体 install）
- [x] §6-3：三服务本地启动；HTTP 接口 URL 与响应不变；活动页/我的奖品/消息页冒烟；Dubbo 兑换边（activity→asset）与用户查询边（→bs）调通
- [x] §6-4 残留为零：旧包名 `com.betterschool.(lottery|points|referral).` 零命中；旧 artifactId（`points-api`、`lottery-*`、`referral-*`、`points-*`）零命中；旧库名 `bs_lottery|bs_points|bs_referral` 在 backend 下零命中（gitignore 的 docker/data 除外）
- [x] §6-5：xxl 两执行器（`marketing-activity-executor` 9997 / `marketing-asset-executor` 9998）在 admin 自动注册；6 个营销 handler 各触发一次成功（含改名的两个 outbox 扫描）；单体 9 handler 不受影响
- [x] §6-6：Vite 代理改后活动页（8082 lottery 前缀）、拉新页（8082 referral 前缀）、积分页（8081）全部走通

## 实现要点（票 05）

**落地**：本票改写面很小——`test-env.sh` 仅头注释一行（6→4 库逻辑票 03/04 已随迁，本票实测 `up` + `reset-test-db` 各一遍确认 `bs_activity`/`bs_asset` 及 `_test` 四库建成）；`backend/CLAUDE.md` §1 加三工程布局句 + §10 路径改 `better-school/bs-app/` 与营销测试 yml 指引（规范条目零改动，code-review 复核 §2–§8 逐字未动）；`backend/docs/README.md` 索引重写 + 构建顺序段（单体 install → asset → activity，activity 依赖 `marketing-asset-api`）；`marketing-services.md` 全文重写为两服务口径（端口/执行器/Dubbo 两边/契约纪律/数据面 bs_activity+bs_asset/前端三前缀两目标）。

**验收记录（全项）**：
- §6-1/2：`mvn -o install`（better-school，含全量测试）→ `mvn -o test`（asset）→ `mvn -o test`（activity）串行全绿：**341 / 11 / 73 用例**。
- §6-3：三 jar 重打（clean package）后 `java -jar` 起 8080/8081/8082；HTTP 冒烟 12/12 PASS（未登录 401 信封、验证码登录、lottery/referral ping 走 `IUserQueryService` 边回用户信息、activity/prizes/draw-chances/my-prizes 响应形状不变）；**兑换边实证**：给 `points_lot` 造 100 分 → `POST /api/lottery/exchange/draw-count` 200 → asset 侧余额 100→0 且 `points_consume` 落 1 行。冒烟脚本 `.scratch/service-restructure-impl/verify-05/smoke05.py`。
- §6-4：旧 FQN / 旧 artifactId grep 零命中；旧库名仅存注释里有意保留的演变说明（与票 03/04 口径一致）。另把本地 MySQL 里 6 个旧库（`bs_lottery`/`bs_points`/`bs_referral` 及 `_test`）DROP 干净（spec §2.3：库改名=本地重建、无可迁移数据）。
- §6-5：活动段 `xxl-job-tasks.sql` 票 04 只落了文件没灌库——本票灌入 admin 库；三执行器 `address_list` 自动注册（9999/9998/9997）；6 个营销 handler 全部有 `trigger_code=200` 的调度记录（jobId 7–12）；单体 9 handler 注册齐全不受影响。
- §6-6：vite 起后 Playwright 登录态注入走 `/marketing`（活动 banner/抽奖次数=兑换到的 1 次/邀请码/拉新数/积分卡片 0）、`/marketing/prizes`（含积分入账 tab）、`/messages` 全渲染，截图存 `verify-05/ui-*.png`。

**冒烟中排除的两个非重构问题（dev 联调库迁移漂移）**：消息页 chat/rooms、notifications 报 999，根因是 dev 库没跑 `migrations/026`（notification.scope/seq）与 `027`（room.source/initiator_id）——补跑后恢复，与本次重构无关；说明 dev 库漂移补法（`docker exec -i bs-mysql sh -c "mysql ... --force" < migrations/0XX.sql`）仍是刚需。

**code-review 采纳项**：docs/README「bs-api 三个服务共同消费」→「两个营销服务」；§7「两条前缀」→「三条前缀、两个目标」；§6 表补 `sql/xxl-job-tasks.sql` 注册落点行。

**不在本票范围**：服务进程与 vite 冒烟后保留常驻（8080/8081/8082/5173）；verify-05 的日志/截图/脚本随票入库作验收证据。
