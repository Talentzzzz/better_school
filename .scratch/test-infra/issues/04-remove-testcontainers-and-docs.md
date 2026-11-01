# 04: 摘除 Testcontainers 依赖与文档收口

**Spec:** `.scratch/test-infra/spec.md` → Implementation Decisions「依赖与构建清理」「约定的落点」；Testing Decisions 验收；Further Notes

**What to build:** 测试进程与 Testcontainers 彻底脱钩——依赖、surefire 环境变量、全仓引用一并清干净；约定写进后端文档，使后续 `/implement` 与 `/tdd` 在后端目录工作时自动遵循「先起环境、再跑测试」，新加入的人第一天就能照文档跑通。

**Blocked by:** 02, 03（两条路径都脱离容器之后才能摘依赖，否则编译与运行都会断）

**Status:** resolved

- [x] 移除 `testcontainers-junit-jupiter` 与 `testcontainers-mysql` 依赖，以及根 pom 里**只为它们存在**的 `testcontainers.version` 属性（该属性同时覆盖 Boot 的依赖管理）
- [x] 移除 surefire 里的 `DOCKER_HOST` 与 `TESTCONTAINERS_RYUK_DISABLED` 两个环境变量（两者都只为 Testcontainers 服务；surefire 插件块只承载这两个变量，整个 `<plugin>` 一并摘除）
- [x] 全仓 `org.testcontainers` 零命中（含 import 与注释里的残留说法），编译干净、无死依赖——另发现并改写了 `backend/docs/architecture/marketing-services.md:71` 一处「Testcontainers 起 Nacos 容器」的残留表述；`dependency:tree` 确认 bs-app test classpath 零 testcontainers
- [x] `backend/CLAUDE.md` 新增「集成测试环境」一节（§10）：跑测试前先 `scripts/test-env.sh up`；隔离口径表（MySQL 库 / Redis 逻辑库 / MinIO 桶 / Nacos / RocketMQ 端口）；集成测试继承基类不自建容器；迁移测试继承 `MigrationTestBase`；改 schema 后 `scripts/test-env.sh reset-test-db`
- [x] `backend/docs/testing.md` 同步作废表述——前置改为 `scripts/test-env.sh up`（含 reset-test-db 假绿提醒）、删 DOCKER_HOST/RYUK 环境注意、「容器」条改写为常驻栈口径、Nacos 三条特殊处理去掉 Testcontainers 说法；文件职责表补 `MigrationTestBase`，基类职责去「容器」
- [x] 验证：**环境未起时跑测试得到明确的连接失败**——实测停掉 `bs-nacos-test` 后跑 `AnonymousRemovalIntegrationTest`，1.3s 内报 `IllegalStateException: 写入 Nacos 共享配置失败` / `Caused by: java.net.ConnectException`（基类 `@BeforeAll` 的 Nacos 硬前置先炸），非静默跳过
- [x] 验证：全量 **323 条既有用例全绿**（断言一字不改，`Failures: 0, Errors: 0, Skipped: 0`）；全量日志零 `Creating container for image`（迁移子集完整日志复核 + classpath 已无 testcontainers，物理不可能）。**实测（2026-09-15，环境已常驻 5h+）：全量 634s（10:34，基线 828s）**；**迁移测试 12 类合计 3.4s（基线 186s，-98%）**——未到 ~20s 目标值而是直接击穿，因为基类改造后迁移部分只剩 JDBC 脚本执行本身

## Answer

**构建清理**（`backend/pom.xml`、`backend/bs-app/pom.xml`）：

- `testcontainers-junit-jupiter` / `testcontainers-mysql` / 根 pom `testcontainers.version` 属性（连注释）全部摘除；surefire `<plugin>` 块只承载那两个环境变量，整个块一并删除。`dependency:tree` 实测 bs-app test classpath 零 testcontainers，全仓（`backend/` + 根目录正式文档）`testcontainers` / `DOCKER_HOST` / `RYUK` 零命中（`.scratch/` 历史票据记录不改）。

**文档收口**：

- `backend/CLAUDE.md` 新增 §10「集成测试环境」：先 `scripts/test-env.sh up` 再跑测试、隔离口径表（MySQL 库 / Redis 逻辑库 / MinIO 桶 / Nacos / RocketMQ 端口）、新测试继承基类不自建容器、迁移测试继承 `MigrationTestBase`、改 schema 后 `scripts/test-env.sh reset-test-db`。
- `backend/docs/testing.md`：前置改为 test-env.sh 流程（含 reset-test-db 假绿提醒）、删 DOCKER_HOST/RYUK 环境注意、「容器」条改「运行环境」、Nacos 三条特殊处理去容器说法、职责表补 `MigrationTestBase`。
- code-review 抓出并已修的三处：CLAUDE.md「唯一事实来源」补基类例外说明（Nacos 地址 / Dubbo 端口走 `System.setProperty`、迁移 JDBC 地址在 `MigrationTestBase`，均为 yml 头注释既定设计）；testing.md 残留「指向测试容器」改「测试 Nacos」；`PostReviewFirstReviewIntegrationTest` 注释 `init.sql` → `schema.sql`（map.md Notes 预留给本票的收口项）。

**票面外一处**：`backend/docs/architecture/marketing-services.md:71`「Testcontainers 起 Nacos 容器」残留表述改写——属「全仓零命中（含注释残留）」的执行。

**实测（2026-09-15，Windows 11 + Docker Desktop，常驻栈已运行 5h+）**：

| 项 | 基线 | 实测 |
| --- | --- | --- |
| 全量 323 条 | 828s | **634s（10:34）** |
| 迁移测试 12 类 | 186s | **3.4s** |
| 日志 `Creating container for image` | 52 次/全量 | **0** |
| 环境未起 | —— | 停 `bs-nacos-test` 后 `AnonymousRemovalIntegrationTest` 1.3s 内报 `ConnectException`（基类 `@BeforeAll` Nacos 硬前置），非静默跳过 |
