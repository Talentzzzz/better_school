# 06 死信管理面的落点与鉴权（决策 + 落地）

Type: grilling
Status: resolved
Blocked by: —

## Question

中心作为独立服务时，管理面挂在自己的 8083 上，前提是「8083 别挂公网」（`TimeoutTaskAdminController`，`GET /dead-letters` + `/resend` + `/cancel`，**无鉴权**）。中心内嵌进业务服务后这个前提消失——接口要么落到 8080（单体对外端口），要么另有落点。要拍：

1. **落点**：两个服务各自一套 `/api/admin/timeout-tasks/**`（营销本期不落代码，先只定口径）？还是只主体有？
2. **鉴权**：单体已有 JWT/角色设施（ADMIN/AUDITOR），接上去，还是维持「内网可达 + 无鉴权」并写进文档？原「不夹分页会被读垮」的顾虑随单表退化后是否还在。
3. **多实例下的查询成本**：单表后死信列表从「16 表广播归并」退化成普通单表分页，是否还需要每页 50 / 页码 1000 的钳制。
4. **重发/取消**是否仍只打 `FAILED` 行（条件 UPDATE，并发安全）。

## 现状依据

`TimeoutTaskAdminController:41,57-95`（现无鉴权、分页被刻意夹住、列表是唯一跨 16 分表广播的查询——`backend/docs/architecture/timeout-center.md §7`）；单体的 JWT/角色设施位置由执行前实测确定。

## Answer

**已落地（2026-10-17）。四问的裁决：**

1. **落点：只主体落，营销不落（连口径带代码都归票 08 的将来时）**。管理面跟随超时表走——「谁有超时表、谁才有管理面」。营销零超时接入方（票 08 实测），现在落一套管理面是空转；营销将来按票 08 规格接入超时中心时，同款再落。主体侧落在 `bs-trigger` 的 `trigger/http/timeout/TimeoutTaskAdminController`，走单体 8080 的 `/api/admin/timeout-tasks/**`（原「8083 内网端口」不复存在）。控制器照 `backend/CLAUDE.md §4/§5` 实现 bs-api 契约接口 `ITimeoutTaskAdminService`、四个 DTO 进 `api.dto`（与 `AdminReviewController` 同款；code-review 指出首版「DTO 自包含在 trigger」违反工程约定，已纠正——原中心「不进业务契约」说的是 Dubbo jar，单体 bs-api 是 HTTP 契约，不冲突）。

2. **鉴权：接单体 JWT/ADMIN**（实测单体设施：`SecurityConfig` 过滤链 + `JwtAuthenticationFilter` 写 `ROLE_xxx` + `SecurityUtils.currentRole()`，`AdminReviewController` 已有 `requireAdmin()` 先例）。登录态由 `anyRequest().authenticated()` 罩住（未登录 401），控制器内三端点都显式收 ADMIN（USER/AUDITOR 均 403）。原「无鉴权 + 部署侧收紧」的缺口随落点迁移一并关掉，不再是已知缺口。

3. **分页钳制：保留 50/1000——票面第 3 问的前提不成立**。内嵌化只把 16 张分表搬进业务库（同库同实例），**分表本身没消失**，死信列表仍是跨 16 分表的广播归并（SQL 无分片键、仓储层这两条读**故意不塞 hint**——全仓唯二的无 hint 语句，塞了会只查一张表翻不出全量死信）。且落进业务库后代价升级：广播与深翻页和用户请求**共享池 10**，「读垮中心」变成「和业务流量抢连接」。所以钳制更有必要，口径原样（越界夹住不报错、下界归一）。

4. **重发/取消：不变，仍只打 FAILED 行**。票 04 已把 `resendDeadLetter` / `cancelDeadLetter`（条件 UPDATE）落进仓储，本票只接 HTTP 面；语义照旧（重发 = FAILED→RETRYING、retry_count 清零、next_fire_time=now、不动 action_time；取消 = FAILED→CANCELED；返回 false/进失败清单 = 该键当前不是死信，无副作用）。

**落地清单**（better-school）：

- `bs-domain`：`model/valobj/DeadLetterPageVO` + `DeadLetterResendOutcomeVO`（照原中心形状，去 callbackGroup）；`ITimeoutRepository` 末段追加 `countDeadLetters()` + `selectDeadLetters(offset, limit)`（javadoc 写明「故意不塞 hint」）；`service/deadletter/` 两件套（转发 + 人工动作告警日志）。
- `bs-infrastructure`：DAO 两方法 + mapper XML 两 select（`WHERE status='FAILED' ORDER BY updated_at DESC, id DESC`，id 兜底同秒撞车）；`TimeoutRepository` 实现绕开 `withSlot`（无 hint 广播归并），类 javadoc 的「别绕开本类调 DAO」补了唯一例外说明。
- `bs-case`：`usecase/timeout/TimeoutTaskDeadLetterCase`（翻页/批量重发双清单/取消，逐条顺序调、不扇线程——池 10 与用户请求共用）。
- `bs-trigger`：控制器 + 四个 DTO，鉴权/钳制见上。
- 测试：`TimeoutDeadLetterSurfaceIntegrationTest` 7 用例——未登录 401、USER/AUDITOR 403、ADMIN 放行；两行摆进不同物理分表（slot 3/11）都翻得出来且 updated_at 倒序（广播归并精确）；分页越界夹住（pageSize 500→50、page 5000→1000、下界归一）；批量重发只打 FAILED（RETRYING 行进失败清单、重开行 retry_count=0/action_time 不动/next_fire_time≈now）；单条重发重复与键错无副作用；取消置 CANCELED、重复取消/键错返回 false。死信前置态用裸 MySQL 直摆（`physicalUpdate` 夹具口径，真实「失败 16 次」路径由票 04 用例覆盖）。
- 文档：arch doc §0 增票 06 条目 + §7 加过时横幅（整节改写归票 09）；`better-school/docs/api/timeout.md`（新建）+ README 索引。

**验收证据**：超时域 7 个测试类 39 用例全绿（含本票 7 个新用例；`TimeoutTaskScanServiceTest` 的 FakeRepository 补了两个新接口方法——`UnsupportedOperationException`，与既有口径一致）。

**执行中发现并纠正的票面偏差**：第 3 问的「退化成普通单表分页」不成立（见上 3）——已按 map 纪律记在本 Answer，不再改票面原文。
