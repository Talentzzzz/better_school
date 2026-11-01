# 01: 测试栈常驻化与环境准备（compose 测试栈 + init.sql 拆段）

**Spec:** `.scratch/test-infra/spec.md` → Solution / Implementation Decisions「拓扑：共享三个、独立两个」「测试栈端口分配」「测试栈只需要两个容器」「`init.sql` 拆段」

**What to build:** 开发者执行一条命令起环境，之后本机同时具备开发栈与测试栈、且全天常驻不拆：开发用的 `better_school` 与测试用的 `better_school_test` 两个库都建在同一个 MySQL 容器里，测试专用的 Nacos（18848/19848）与 RocketMQ namesrv（19876）在各自容器里就绪。做完这一票，后面两票（迁移测试、API 测试）才有地方可连。**本票不碰任何业务代码与测试断言**。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] compose 新增测试栈**两个**容器：Nacos（容器名一眼可辨，映射 `18848:8848` 与 `19848:9848`，与开发 Nacos 同款 `MODE=standalone` + 三个 `NACOS_AUTH_*` 引导变量，独立日志卷）与 RocketMQ namesrv（映射 `19876:9876`，**不起 broker**）
- [x] 共用容器 mysql / redis / minio **不复制**，只追加测试 host 端口映射（`13306:3306` / `16379:6379` / `19000:9000` / `19001:9001`）；开发端口原样保留，一套容器两套 host 端口
- [x] `sql/init.sql` 拆为三份：`sql/schema.sql`（纯 DDL + 种子数据，**不含** `CREATE DATABASE` / `USE`，保留 `SET NAMES utf8mb4`）、`sql/init-dev.sql`（建 `better_school` 并 `USE`，再引入 schema）、`sql/init-test.sql`（建 `better_school_test` 并 `USE`，再引入 schema）
- [x] 新增起环境脚本（用户已选「加一个后端脚本」）：一条命令 = 起 compose + 等就绪 + 建好两个库（schema 全是 `CREATE TABLE IF NOT EXISTS` / `INSERT IGNORE`，可重复执行）；另提供「重建测试库」子命令（先 DROP 再建），作为 schema 漂移的兜底路径（story 16）
- [x] `docker-compose.yml` 头注释同步：原手工命令 `< sql/init.sql` 在去掉 `USE` 后会报 `No database selected`——改为带库名、并区分 dev（`init-dev.sql`）与 test（`init-test.sql`）两套说明；仍保留 `--default-character-set=utf8mb4`
- [x] **过渡性一行**：`CommunityApiTestBase` 里把 `sql/init.sql` bind 进 MySQL 容器的那一处改指向 `init-dev.sql`（测试容器此刻仍由 Testcontainers 起，挂 dev init 即行为不变）。这是本票唯一允许的 Java 改动，票 03 会连方法一起删掉
- [x] 验证：`docker exec` 进 `bs-mysql` 看到两个库、各自表齐全、种子敏感词在；测试 Nacos `18848` 的 readiness 端点返回 200；`19876` 可连
- [x] 既有 323 用例仍然全绿（拆段 + 挂载跟随之后没有回归）

**Notes:** 测试侧配置里**彻底不出现 `better_school` 字面量**（spec 点名的「静默清库」风险），`better_school_test` 一眼可辨；共用 MySQL 的第二套端口正是为了让这个区分在配置里看得见。

## Answer

### 落地清单

| 项 | 落点 |
| --- | --- |
| compose 测试栈两容器 | `docker-compose.yml`：`bs-nacos-test`（18848:8848 / 19848:9848）、`bs-rocketmq-namesrv-test`（19876:9876，无 broker），放在文件尾部独立成节 |
| 共用容器两套 host 端口 | mysql `13306:3306`、redis `16379:6379`、minio `19000:9000` + `19001:9001`，开发端口原样保留 |
| SQL 拆段 | `sql/init.sql` → `sql/schema.sql`（488 行，`git mv` 保历史）+ `sql/init-dev.sql` / `sql/init-test.sql`（各 9 行，只建库 + `USE`） |
| 起环境脚本 | 新增 `backend/scripts/test-env.sh`（票 01 定的形状）：`up`（默认）/ `reset-test-db` / `help` |
| 头注释 | compose 头注释 1 改为脚本优先 + 两套 cat 拼接命令；新增注释 5 讲测试栈拓扑与端口 |

### 两处对票面的有意偏离（都写在票面之外，落地时更正是必要的）

1. **过渡性一行改的是 `schema.sql` 而不是 `init-dev.sql`，并补了 `withDatabaseName(DB_NAME)`**。
   照票面把 bind 指向 `init-dev.sql` 会让测试库**一张表都没有**——拆段后 DDL 全在 `schema.sql`，而
   `init-dev.sql` 只剩「建库 + USE」，且容器 entrypoint 的每个 init 文件是**各自一条 mysql 连接**
   （`USE` 不跨连接），就算再挂一份 `schema.sql` 也会落回 `MYSQL_DATABASE` 默认的 `test` 库。
   实际落法：bind `schema.sql` → `/docker-entrypoint-initdb.d/schema.sql`，并给容器
   `MYSQL_DATABASE=better_school`（`withDatabaseName(DB_NAME)`）——entrypoint 先建库、再按
   `--database=better_school` 执行该脚本（已 `docker run --entrypoint` 读官方 entrypoint 源码核实
   `docker_process_sql` 会前置 `--database="$MYSQL_DATABASE"`）。**票 03 要删的是两处**：
   `withFileSystemBind(...)` 与 `withDatabaseName(DB_NAME)` 都随 `resolveSchemaSqlPath()` 一起走。
2. **`init-dev.sql` / `init-test.sql` 的「引入 schema」靠拼接，不能靠 `SOURCE`**。
   容器内没有本机 `sql/` 目录，`source schema.sql` 无从解析；`cat sql/init-dev.sql sql/schema.sql | mysql`
   把两段喂给**同一个连接**，`USE` 因此对 DDL 生效。头注释与脚本都按这个形状写。
   （副作用：单喂 `schema.sql` 会 `No database selected` —— 这正是票面点名的那个坑，只是修法从「带库名」
   变成「拼接 + `--database`」。）

### 起环境脚本（`scripts/test-env.sh`）

- `up`（默认）= `docker compose up -d` → 轮询 7 个中间件的 healthcheck（**不含** broker / dashboard / xxl-job-admin / ES：测试不依赖，且 ES 要宿主机 `sysctl` 才起）→ 按序拼接建两个库。全幂等，可重复跑。
- `reset-test-db` = 等 mysql 就绪 → `DROP DATABASE IF EXISTS better_school_test`（库名走变量，脚本里不写死任何库名）→ 重建。
- 就绪判定用 `docker inspect` 的 `.State.Health.Status`；容器缺失/超时都打明确的一行并 `exit 1`。
- 这是本仓第一个 `scripts/` 目录（map 的 Not yet specified 项，票 01 落定）。

### 验证（2026-09-15 实测）

- `bash scripts/test-env.sh up` 一次跑通：7 个中间件全部 `healthy`；`better_school` 与 `better_school_test` **各 28 张表**。
- 种子词是真 UTF-8 而非乱码：`better_school_test.sensitive_word` 8 行、`HEX(word)` = `E4BBA3E88083`（「代考」）、`CHAR_LENGTH=2` 计数 8。
- 测试 Nacos readiness：`curl 127.0.0.1:18848/nacos/v1/console/health/readiness` → **200**。
- `19876` 可连：容器内与宿主机 `/dev/tcp` 都通。
- `reset-test-db` 跑过一次：DROP + 重建后回到 28 张表，开发库 untouched。
- 既有用例：先单类 `PostReviewFirstReviewIntegrationTest` 5/5 绿（证明过渡性 bind 改动生效），再全量 ——
  **`Tests run: 323, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS，13:50 min**。断言一字未改
  （`git status` 里 Java 只动了 `CommunityApiTestBase.java` 一个文件）。耗时仍与基线 828s 持平属预期：
  本票不摘 Testcontainers，提速在票 02/03。

### code-review 采纳项

- **Spec 轴**：`test-env.sh` 里 `MIDDLEWARE` 的注释称「测试进程真正要连的 7 个中间件」，但 `bs-nacos`（开发 8848）测试根本不连 → 改述为「起完要等就绪的 7 个中间件（开发侧 + 测试侧）」；`docker-compose.yml` 的「测试栈不起 broker」不准确（`up -d` 会把开发 broker 一起起了）→ 改成「测试栈**只有 namesrv、没有配套 broker**，开发栈的 broker 照常起、连的是开发 namesrv」。两条都是措辞级修正，行为不变。
- **Standards 轴**：`sql/migrations/` 里 001 / 002 / 004 / 020 四个脚本头注释还在说「init.sql」（001/002/004/020 都点名这个文件，且**都不在**本 effort 已声明的两个延后清单里）→ 已改为 `schema.sql`。改完重跑 `RemoveAnonymousMigrationTest` + `ErrandEvaluationPerPersonMigrationTest` 验证注释改动没碰坏脚本（2/2 绿）。
- **未采纳（判定为可接受）**：① `init-dev.sql` / `init-test.sql` / `test-env.sh` / compose 四处都写了「两段必须拼给同一个连接」——已按 issue-tracker「要点 + 链接」的口径把完整解释收敛到 compose 头注释 1 一处，其余只留一行结论 + 指向；② 两个测试容器的 healthcheck 与开发版逐字重复——本仓既有风格就是显式重复（开发 nacos / namesrv 本就各写一份），引入 YAML anchor 属新增约定，不在本票；③ 端口集合在 compose 注释、脚本 `TEST_PORTS` 提示行、`application-test.yml` 各出现一次——`application-test.yml` 是事实来源（票 03 落定），另两处是给人看的提示，不构成同步负担。

### 已知波及 / 不在本票范围

- `backend/docs/testing.md` 的「建库走 sql/init.sql」「容器：Testcontainers 起…」属**票 04**（该票 checklist 点名）；`PostReviewFirstReviewIntegrationTest` 一行注释同理。
- 12 个迁移测试 javadoc 里的「对齐 init.sql 在 NNN 已应用」属**票 02**（该票本就要重写这 12 个子类）。
- `.scratch/marketing-impl/` 票 07 写「`init.sql` 原地不动」已过期（该文件已 `git mv` 成 `schema.sql`），且它同样要改 compose 头注释——**未改那张票**，已在 map 的 Not yet specified 里标注给其归属 effort。
- 依票面要求，`application-test.yml`、`@DynamicPropertySource`、五个 Testcontainers 容器、迁移测试的 `@Container` 全部原样未动（票 02/03/04）。
- 全量耗时本票不动（828s → 13:50 min 持平）：本票只搭台，提速在票 02/03。

