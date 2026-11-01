# 02: 迁移测试脱离容器（MigrationTestBase）

**Spec:** `.scratch/test-infra/spec.md` → Implementation Decisions「迁移测试：`MigrationTestBase`」

**What to build:** `integration/sql/` 下 12 个迁移脚本测试不再各起一个一次性 MySQL，而是全部连常驻测试栈（`localhost:13306`）上的一个共享连接，每个用例用**自己的独立 database**（等价于「每类一个新容器」的隔离）。做完之后迁移部分耗时由 186s 降到 ~20s，且 `docker ps` 里不再因为跑迁移测试多出容器。

**Blocked by:** 01（常驻 MySQL 与 `init-test.sql` 是前置）

**Status:** resolved

- [x] 新增共享基类 `MigrationTestBase`（`integration/sql` 包）：一个指向常驻测试栈的连接，`@BeforeEach` 对**本类的临时库**执行 `DROP DATABASE IF EXISTS` + `CREATE DATABASE` 再 `USE`（每用例一份干净 schema，隔离不被残留污染）
- [x] 临时库命名 = **固定前缀 + 类名**（用户已选，如 `bs_it_remove_anonymous`）：一眼可辨、不撞开发库、且能在 `docker exec` 进 `bs-mysql` 后直接看到，便于排查失败用例
- [x] **硬规则**：`DROP` / `CREATE DATABASE` 语句里禁止出现 `better_school` 字面量——临时库名只能由固定前缀 + 类名拼出，杜绝误清开发数据
- [x] 基类收敛 12 个类里逐字复制的样板：容器声明（×12）、迁移脚本定位（两候选目录的解析逻辑，×11）、`scalar`（×10）、`columnComment`（×8）、`count`（×6）
- [x] 12 个子类只保留自己的三样东西：迁移前的旧表 DDL 常量、存量数据插入、迁移后断言；**断言一字不改**
- [x] 12 个类全绿；全仓 `integration/sql/` 下 `org.testcontainers` 零命中；日志里不再出现 `Creating container for image`
- [x] 不起 Spring 上下文这一点不变（迁移测试仍是 JDBC + `ScriptUtils` 跑真实脚本文件）

## Answer

### 落地清单

| 项 | 落点 |
| --- | --- |
| 共享基类 | `integration/sql/MigrationTestBase.java`（新增）：连常驻 13306，`@BeforeEach` 重建本类临时库（`bs_it_` + 类名蛇形），`@AfterEach` 关连接；临时库**保留不删**，`docker exec` 可直接查看 |
| 收敛的样板 | `runDdl` / `runMigrationScript`（实例方法，需库名）+ `scalar` / `count` / `columnComment` / `columnExists` / `indexNameOnColumn` / `uniqueKeyColumns` / 脚本两候选目录定位（静态方法，签名与原逐字拷贝一致，断言调用面零改动） |
| 12 个子类 | 删 `@Testcontainers` / `@Container` / `MYSQL_IMAGE` / 各自的辅助方法拷贝，`extends MigrationTestBase`；DDL 常量、插入、断言逐字保留（已逐条 diff 核对） |
| 耗时 | 12 类合计 **~3.5s**（基线 186s，其中断言仅 3.0s）；`docker ps` 不再因迁移测试多出容器 |

### 关键发现与两处对票面的有意偏离（落地时更正，票面未预见）

1. **迁移脚本自带 `USE better_school;`，在共用常驻 MySQL 上指向的是开发库**。原方案靠一次性容器的库名恰好也叫 `better_school` 才成立；常驻栈上这条 USE 会把连接切进开发 `better_school`（首次试跑已实际发生：017 的三条测试数据写进了开发库 `errand_order_member`，已按 `joined_at` 精确清除并核实引用的订单 id 在开发库不存在）。修法：`runMigrationScript` 读入真实脚本文件后，把整行的 `USE <库>` 重定向到本类临时库再执行；并加防线——若脚本出现**未能整行匹配**的 USE 语句（如行尾带注释）直接抛错，宁可失败不可静默写开发库。这也是 spec「静默清库」残留风险在迁移侧的具体堵法。
2. **「一个共享 MySQL 连接」落成了每用例一条新连接**：每用例要 `USE` 到自己的临时库，连接必须随用例开合；对常驻库代价可忽略。语义（共享一个 MySQL 服务器）不变。
3. 其余小偏离：收敛面比票面列举多收了 `indexNameOnColumn`（×2）/ `uniqueKeyColumns`（×2）/ `columnExists`（×1）/ `runDdl`（×12 三行样板），均在 spec「`scalar` / `columnComment` / `count` 等既有辅助方法」的「等」字内；12 个子类 javadoc 的「对齐 init.sql」同步改为 `schema.sql`、「镜像对齐」句删除（票 01 已在 Notes 里把这两处记到本票）。

### 验证（2026-09-15 实测）

- `mvn -pl bs-app test -Dtest='*MigrationTest'`：**12/12 绿，合计 ~3.5s**；`bs_it_*` 12 个临时库在 `docker exec` 可见、各含本类 schema。
- 全量 `mvn test`：272 条执行 0 失败 + 10 个类因 **Docker daemon 创建容器返回 500**（`CommunityApiTestBase` 的 Nacos，票 03 范围、本次未动；与迁移改动无关）报类级错——单独重跑这 10 类 **61/61 全绿**，确认瞬态环境故障；合计口径与票 01 的 323 全绿一致。
- `integration/sql/` 下 `org.testcontainers` / `MySQLContainer` / `@Testcontainers` 全仓 grep 零命中；日志无 `Creating container for image`（迁移部分）。
- 开发库 `better_school` 校验未受试跑污染（`errand_order_member` 回到原有 2 行）。

### code-review 采纳项

- **Spec 轴**：USE 重定向的静默失效风险（两轴共同点名）→ 采纳为上述「未能整行匹配即抛错」防线；「每用例一条连接」偏离 → 记入偏离 2；「共享连接」字面差异不再改。
- **Standards 轴**：辅助方法实例/静态形态混合 → 判定为可接受（静态查询辅助保持与断言原文一致的调用形态，实例化仅限需要库名的 `runDdl` / `runMigrationScript`）；`protected Connection` 可变字段 → JUnit 基类惯用，不包中间层。

### 已知波及 / 不在本票范围

- Testcontainers 依赖本体与 surefire 的 `DOCKER_HOST` / `TESTCONTAINERS_RYUK_DISABLED` 属票 04；`CommunityApiTestBase` 五容器与 `application-test.yml` 固定值属票 03（本轮全量里那 10 个 Nacos 容器启动 500 的类都在这条路径上）。
- 临时库常驻累积（12 个 `bs_it_*`）是票面设计（可辨、便于排查），不需要清理任务；如需回收可 `DROP DATABASE bs_it_xxx` 任意单个。
