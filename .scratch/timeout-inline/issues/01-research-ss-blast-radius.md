# 01 调研：ShardingSphere 挂上业务 datasource 的爆炸半径

Type: research
Status: resolved
Blocked by: —

## Question

把 `backend/better-school/` 唯一的 datasource 换成 `jdbc:shardingsphere:classpath:sharding.yaml`（`driver-class-name = org.apache.shardingsphere.driver.ShardingSphereDriver`，YAML 里指真实 `jdbc:mysql://.../better_school`），**只给 `timeout_task` 一个逻辑表配 HINT 分片规则**（16 张物理表在同一 schema），那 30 张**没有任何分片规则**的业务表会发生什么？

必须回答（每条要一手来源或本地 5.5.3 jar 的证据）：

1. 未配置表（无 `!SHARDING`、无 `!SINGLE`）在「只有一个 datasource」时是**透明直通**、还是必须显式声明、还是路由期报错？5.5.x 的 `SingleTableRule` 默认行为、`single-table.default-data-source-name` 是否存在。
2. 「一个分片逻辑表 + 一堆未配置表」在同一逻辑 datasource 上是不是官方支持/文档化的用法。
3. **事务**：同一个 `TransactionTemplate` 里既写未分片表、又写 hint 路由的分片表，是否仍是**一条连接、一个本地事务**？有无官方 caveat。
4. 把驱动挂上既有应用主 datasource 的**运行期风险**：SQL 解析失败是 fail-fast 还是静默回退？是否额外占用元数据连接（对池 10 的影响）？`HintManager` ThreadLocal 纪律、MyBatis 拦截器/`@MapperScan`/mapper XML 是否受影响；`useGeneratedKeys` / `LAST_INSERT_ID()` / `ON DUPLICATE KEY UPDATE` / `INSERT IGNORE` / 多值 `IN (...)` 批量 UPDATE / `SHOW` / `information_schema` 是否照常。
5. 5.5.3 已知坑（现工程已记：commons-lang3 ≥ 3.18.0、缺特性 artifact 报错不提示、表名零填充、Spring `${}` 到不了 YAML 那层）之外，坐在生产主 datasource 上还有哪些。
6. 若结论是「不可行/风险过高」：5.5.x 有没有**官方支持**的替代——例如只在超时仓储上用一个独立的 `ShardingSphereDataSource` bean、业务仓储继续用原 datasource？并说明那种形态下「同事务」为什么不成立（另开 datasource = 另开连接 = 另一个事务；除非有受支持的共享/多数据源事务机制，需核实是否真的存在）。

## Answer

**结论：口径 3 成立——能挂，但必须显式补一条 `!SINGLE` / `tables: ["*.*.*"]`。不需要退化成 MyBatis 表名后缀路由。**

补齐这条之后，30 张业务表**完全透明**（SELECT/INSERT/JOIN/`EXPLAIN`/子查询/`FOR UPDATE`/ODKU/`INSERT IGNORE`/`REPLACE`/批量 UPDATE/`SHOW`/`information_schema`/`TRUNCATE`/generated keys 全部照常），hint 路由分表与业务写入仍是**同一条 MySQL session、同一个本地事务**，一个 `rollback` 三张表全清。缺这条则**整库不可用**：逻辑库只暴露 `timeout_task` 一张表，其余业务表一律 `TableNotFoundException`。

期限证据全部来自**独立临时 MySQL（127.0.0.1:3307，root 空密码）**上的真实 `ShardingSphereDriver` + `jdbc:shardingsphere:classpath:*.yaml`（不是程序化 API 近似），用的是 `timeout-center-app` fat jar 里解出的 5.5.3 全套 jar（`BOOT-INF/lib`，218 个）。

### 收尾那一条（本次会话新做，推翻了上一轮的「偶发」判断）

手递文档 §4 记的「`probe-star.yaml` 形态带 hint 写分表偶发 `TableNotFoundException: timeout_task_03`」**已定位为探针自身缺陷，不是产品行为**，并且已用干净复现钉死。三处独立缺陷叠在一起：

1. **上一轮探针直接点名物理分表**（`SELECT COUNT(*) FROM timeout_task_03`）。在分片逻辑表下，物理分表**本来就不该**出现在逻辑 metadata 里，直接点名必然 `TableNotFoundException`——这是正确行为。上一轮把它记成「hinted write 失败」，是探针把「路由能力」和「metadata 可见性」混成了一件事。本次实测：`logical db 'logic_db' -> visible tables(1): [timeout_task]`，物理分表一张都不可见。
2. **四个变体共用同一个 `probe` schema、同一个 `jdbc:mysql://.../probe` URL**，而 driver 的 `DATA_SOURCE_CACHE` 按 URL 缓存 + 探针每轮 `DELETE` 互相踩。实测 `DATA_SOURCE_CACHE` 确实一个 YAML 一条（收尾那轮 4 条 key）。
3. **断言口径把「本事务插入的行」和「同一分表上的历史行」混了**。最后钉死它的那条：`one rollback clears all three tables :: po=0 tt=1 rt=0` 里的 `tt=1` 是 Q2 循环刚提交在 `timeout_task_05` 的那一行，不是回滚残留。把目标分表换到 Q2 没碰过的 `timeout_task_06` 并先断言它为空之后，同一条断言变 `po=0 tt=0 rt=0` 通过（`confirm2-out.txt`）。这正是手递文档怀疑的那一类「探针自身缺陷」。

**已排除产品侧冲突**：`!SINGLE tables: ["*.*.*"]` 与 `!SHARDING` 的 16 张物理分表**不冲突**。5.5.3 的 `SingleTableDataNodeLoader.load()` 在走通配分支前先用 `SingleTableLoadUtils.getExcludedTables(rules)` 把分片物理表摘掉，实测 `singleTableDataNodes (4) keys=[errand_order, product_order, review_task, user_account]` —— 16 张 `timeout_task_XX` 一张都不在里面。所以口径 3 的处方不用改。

### 逐问回答

**Q1 —— 未配置表：不是透明直通，是路由期报错；必须显式声明。**

5.5.3 在没有任何 `!SINGLE` 时**并不缺规则**：`SingleRule` 自动存在，只是空（`confirm-info.txt` 段 A：`rule SingleRule :: ...@5cdd09b1` + `singleTableDataNodes (0) keys=[]`）。缺的是**显式声明**。

- **不声明 → 整库报错**：`logical db 'logic_db' visible tables(1): [timeout_task]`。业务表 SELECT/INSERT/JOIN/`EXPLAIN`/子查询/`FOR UPDATE`/`TRUNCATE`/ODKU/`INSERT IGNORE`/`REPLACE`/批量 UPDATE/generated keys **全部** `TableNotFoundException: Table or view 'xxx' does not exist.`，且这是解析/元数据校验期的错，**没碰到数据库**。（`confirm2-out.txt` 变体 C/E/F，30 条 FAIL 全是这个；历史 `final-out.txt` 段 A、`named-out.txt` 段 A 同结论。）
- **声明 `!SINGLE tables: ["*.*.*"]` → 完全透明**：`visible tables(5): [errand_order, product_order, review_task, timeout_task, user_account]`，4/4 业务表可读、Q4 特性矩阵全绿（`confirm2-out.txt` 变体 D）。
- **`single-table.default-data-source-name` 在 5.5.3 已不存在**。本地 jar 里全量搜 `single-table.default` / `default-data-source` **零命中**，只有 `defaultDataSource`（`SingleRuleConfiguration.class` / `YamlSingleRuleConfiguration.class` / `SingleRuleConfigurationEmptyChecker.class` 等）。`javap` 实证：`SingleRuleConfiguration` 的私有字段是 `Collection<String> tables` + `String defaultDataSource`，公开面是 `getTables()` / `setTables(...)` / `getDefaultDataSource()`（返 `Optional<String>`）/ `setDefaultDataSource(...)` / `getLogicTableNames()` / 两参构造；YAML 侧 `YamlSingleRuleConfiguration` 同名 `tables` / `defaultDataSource` 两个属性。所以 YAML 键名就是 `tables` 与 `defaultDataSource`。
- **只写 `defaultDataSource: ds0` 不写 `tables` 无效**：`singleTableDataNodes (0)`、业务表全挂（`confirm2-out.txt` 变体 E）。
- **`tables` 的写法有硬要求**：源码里 `SingleTableDataNodeLoader.load()` 第 8–33 字节先判 `tables.isEmpty() && featureRequired.isEmpty()` 直接返回空 Map；第 47–68 字节只对 `"*.*"` 与 `"*.*.*"` 走「扫全部表」分支；其余形态走 `getConfiguredTableMap()`。实测（`loader-out.txt`）：`["*.*.*"] -> size=20`、`["*.*"] -> size=20`，而 `["probe.*.*"] -> size=0`、`["probe.*"] -> size=0`、逐张 `"probe.user_account"` 形式 `-> size=0`。**即：只有 `*.*.*` / `*.*` 这两个通配字面量有效，"<schema>.<table>" 逐张列出无效**（后者看起来像「显式声明」，实际落进 `getConfiguredTableMap` 分支且不被识别）。
- **`*.*.*` 是 schema 级的**，会带上该 datasource 能看到的**所有** schema 的表（`loader-out.txt` 里 `probe` / `logic_db` / `PROBE` 三种写法都返回同一批表）。而 `!SINGLE` 只影响「逻辑库对哪些表做单表路由」，**不新增数据库访问权限**——datasource 账号本来就能读那个 schema。新库上线时需要复核 `singleTableDataNodes` 集合是否符合预期（见「风险」）。

**Q2 —— 是官方支持的用法，但前提是显式 `!SINGLE`。**

`!SINGLE` 规则本身就是 5.5.x 为「同一 datasource 上既有分片表、又有大量普通表」这个场景提供的：`SingleTableLoadUtils.getAllTablesNodeStr(DatabaseType)` / `getAllTablesNodeStrFromDataSource(...)` 就是为生成 `*.*.*` 这种「全部表都是单表」的字面量准备的公开 API，`load()` 里也专门先扣掉分片物理表（`getExcludedTables`）。所以「一个 `!SHARDING` 逻辑表 + 一堆 `!SINGLE` 普通表」是**一等公民组合**，不是 hack。

反过来，「什么都不声明就让未配置表直通」**不是**任何版本的默认行为。

**Q3 —— 是。一条连接、一个本地事务。这是本票最强的一条结论。**

在 `!SINGLE tables: ["*.*.*"]` 形态下，同一事务里「未分片表写 → hint 路由分片表写 → 未分片表写」：

- `SELECT CONNECTION_ID()` 三次全同：`ids=172,172,172`（`confirm2-out.txt` 变体 D）。
- 事务未提交时，另一个连接**看不见**那条分片行进物理表：`uncommitted sharded row is invisible to other sessions :: rowsFromAnotherConnection=0`。
- 一条 `rollback()` 三张表全清：`po=0 tt=0 rt=0`。
- 独立复核（`rollback-out.txt`）：hint 分片插入 → 从另一连接读**不可见** → rollback 后 16 张分表全空；`savepoint` 亦可用（`final-out.txt` 段 C）。

**官方 caveat 就是数据源边界本身**：同一逻辑 datasource 内 = 同一 Connection = 同一本地事务；跨 datasource（哪怕两个 `ShardingSphereDataSource` 指向同一个 MySQL 实例/同一个库）**不成立**——见 Q6。另注：`dbCount > 1` 会让超时行落到另一个库，本 effort 的核心前提随之失效（map Notes 2 的 ⚠️ 硬约束），本期 `dbCount` 恒为 1。

**Q4 —— 运行期风险：解析失败是 fail-fast（不是静默回退），`HintManager` 纪律要按 5.5.3 的实际类改口径，元数据连接开销很小。**

- **fail-fast，且有安全网**：分片表**写**语句漏塞 hint 会被当场拒掉——`java.sql.SQLException: Please check your sharding conditions ... to avoid same record in table 'timeout_task' routing to multiple data nodes.`（`confirm2-out.txt` 全变体 Q2b 通过；`final-out.txt` Q2 同结论）。未配置表则是 `TableNotFoundException`（解析/元数据期）。两种都**不会静默写错表**。分片表**读**语句漏塞 hint 是退化成 16 表广播（结果仍对，但表级归属失效）——现 `sharding.yaml` 头注释第 21–23 行已记，本次复核一致。
- **`HintManager` 不是纯 ThreadLocal，是 `com.alibaba.ttl.TransmittableThreadLocal`**，且**会往「在 hint 作用域内创建的」子线程透传**。精确口径（`verify-out.txt`，5 条 4 通过 + 1 条界定）：

  | 场景 | 子线程看得见父 hint？ |
  | --- | --- |
  | 线程**在 hint 作用域内创建**（`new Thread`，作用域内 start） | **看得见**（`child sees [timeout_task_11]`） |
  | 线程**在 hint 作用域之前创建**、作用域内 start | **看不见**（`child sees []`） |
  | 池化 worker（`ExecutorService.submit`） | **看得见**（`worker sees [timeout_task_15]`） |
  | 同一 worker 的**下一个任务** | **看不见**（`next task isInstantiated=false`） |
  | `close()` **之后**创建的线程 | 看不见（`child sees []`） |

  TTL 只在 `capture()`（即 `start()`/`submit()` 那一刻）快照，所以口径是**「hint 作用域必须先于子线程的创建/提交」**，而不是 arch doc §6 / `TimeoutTaskRepository:149` 现在写的「必须同线程」。二者在这套代码里**结论一致**（`withSlotHint` 在 action 之前开作用域，扫描是「一槽位一虚拟线程」、虚拟线程在作用域内创建），但**理由和边界不同**：按「同线程」写会让人以为可以把 hint 设在父线程、再去子线程跑 mapper——那条**只在子线程创建点晚于作用域时才成立**，是个脆的隐式耦合。建议 arch doc §6 改成「作用域先于线程创建」。
- **`useGeneratedKeys` / `LAST_INSERT_ID()` / ODKU / `INSERT IGNORE` / `REPLACE` / 多值 `IN` 批量 UPDATE / `SHOW` / `SHOW CREATE TABLE` / `EXPLAIN` / 子查询 / `FOR UPDATE` / `TRUNCATE` / `information_schema` / `JOIN` 全部照常**（前提：`!SINGLE` 已声明）——`confirm2-out.txt` 变体 D 的 Q4 矩阵 14/14 通过，含未配置表的 generated keys（`key=4`）与分片表的 generated keys（`key=1`）。未声明时上述凡涉及业务表的一律挂。
- **MyBatis / `@MapperScan` / mapper XML 不受影响**：驱动在 `DriverManager` / JDBC 层，MyBatis 只看到 `Connection`/`PreparedStatement`；本次用 `PreparedStatement` + `RETURN_GENERATED_KEYS` 走 hint 插入亦通过（`gap-out.txt`）。
- **元数据连接开销很小**：一个变体一次 `PROCESSLIST` 计数 = **2**（`confirm2-out.txt` 四个变体全是 2），不是随表数增长的开销；metadata 读取发生在初始化/刷新期，不在每语句路径上。
- **元数据/库名错配是隐性风险**：不写 `databaseName` 时逻辑库名恒为 `logic_db`，与 datasource 里的真实 schema 名（`better_school`）不一致（`confirm-info.txt` 段 A、`confirm2-out.txt` 变体 C）。`ds0.timeout_task_XX` 形式仍工作，但任何按逻辑库名对照的排查/工具都会对不上号。**建议显式写 `databaseName: better_school`**（`gap-out.txt` 实测：带 `!SINGLE` + 不写 `databaseName` 也能跑，所以这条是**可读性/可运维性建议**，不是功能必需）。

**Q5 —— 坐在生产主 datasource 上，除已记四条之外的新坑。**

1. **`!SINGLE` 的缺失是静默的**：没有 `!SINGLE` 时驱动**照常启动成功**（`ShardingSphere-JDBC Standalone mode started successfully.`），直到第一条业务 SQL 才 `TableNotFoundException`。配置错了不在启动期暴露。
2. **不写 `!SINGLE` = 整库锁死**，不是「部分表不可用」——`better-school` 有 30 张业务表，全部会挂。这使这条配置成为**上线阻断项**，值得在启动期自检（见「建议」）。
3. **`tables` 只认 `*.*.*` / `*.*` 两个字面量**，`"<schema>.<table>"` 逐张列出静默无效（`size=0`），且**不报错**。写错形态同样是启动成功、运行期挂。
4. **`!SINGLE` 是 schema 级通配**：`*.*.*` 把该 datasource 能看到的**所有** schema 的表都认成单表（`loader-out.txt` 实测 `probe`/`logic_db`/`PROBE` 都同一批）。生产库里有别的库/schema 时，`singleTableDataNodes` 集合会比预期大；新增表不需要改 YAML（好），但也意味着**新库没有「只有这些表可见」的白名单保护**（不好）。
5. **`databaseName` 默认 `logic_db`**（见 Q4 末），与真实 schema 名不一致。
6. **`HintManager` 一旦 `isInstantiated()`，5.5.3 会关掉 `ShardingSpherePreparedStatement` 的语句缓存**（`isStatementsCacheable()`：`StorageConnectorReusableRuleAttribute` 数量 == 规则总数 **且** `!HintManager.isInstantiated()`）。对现工程无影响（只有 `!SHARDING` 一条规则时该属性数量必然对不上，缓存本来就关着），但若将来加别的规则且某条支持 reusable 属性，会出现「有无 hint 两条不同的缓存路径」，值得知道。
7. **内外两层池仍要各自给足**（现 `sharding.yaml` 第 31–36 行已记）：外层 Spring Hikari（业务池 10）包住内层 driver 池（`maximumPoolSize: 24`）。本次实测单变体 `PROCESSLIST = 2`，说明内层空闲占用很低，但并发上限仍由两层的最小值决定。

**Q6 —— 不需要退化成 MyBatis 表名后缀路由；「分裂 datasource」形态则确认不成立。**

- **正规解法就是 Q1 那条配置**：`!SINGLE tables: ["*.*.*"]`，纯 YAML 改动，无需代码/无需另建 bean。已实测（`gap-out.txt`，6/6 通过，且**不写 `databaseName` 也成立**，所以与现 YAML 形态的差异只有新增一条规则）。
- **「分裂 datasource」可作替代形态，但同事务不成立**——`probe/probe-out.txt` 段 E 实测：业务事务回滚**不会**带走超时行（`rows=1`，未回滚）。原因即 Q3 的 caveat：另开 `ShardingSphereDataSource` = 另开连接 = 另一个本地事务。
- **「有没有受支持的共享/多数据源事务机制」——查过了**：5.5.3 里带 `shardingsphere-transaction-xa-core` + `shardingsphere-transaction-xa-spi`（XA 分布式事务）与 `shardingsphere-transaction-api`。但 XA 解决的是**跨库**原子性，**不是**把两个独立 `DataSource` 合并成一个本地事务；把它用在本场景等于引入 2PC 协调（以及 XA 对 MySQL 的 `XA START/END/PREPARE/COMMIT` 依赖），即回到改造前那套复杂度。**结论：没有能保住「同一条连接、同一个本地事务」语义的替代形态**——要么挂同一个逻辑 datasource（本票推荐的处方），要么放弃同事务。

### 建议（给实现票，不在本票落代码）

1. `backend/timeout-center/timeout-center-app/src/main/resources/sharding.yaml` 与 `src/test/resources/sharding-test.yaml` 各加一条 `!SINGLE { tables: ["*.*.*"] }`，**放在 `!SHARDING` 之前**（`confirm2-out.txt` 变体 D 与 `gap-out.txt` 都是这个顺序）。
2. 给两个 YAML 补 `databaseName:`（与其真实 schema 名一致），消除 `logic_db` 错配。
3. 加两个启动期自检（便宜且能挡住上面最贵的两个坑）：
   - `SingleRule.getSingleTableDataNodes()` 非空，且**包含几张已知业务表**（例：`better_school` 侧断言 `user_account` 之类在其中）；为空即启动失败。
   - 断言 `timeout_task` 的 16 个物理分表**不在** `singleTableDataNodes` 里，确保 `!SINGLE` 没有把分片表也吃成单表。
4. arch doc §6 / `TimeoutTaskRepository:149` 的「hint 与 mapper 调用必须同线程」改成「**hint 作用域必须先于子线程的创建/提交**」，并说明 TTL 语义（见 Q4 表）。

### 未做 / 留给下一票

- **无 hint 广播 vs 单表的连接数开销曲线**没有测。本次只测到单变体 `PROCESSLIST = 2`（空闲口径），不是并发扫描下的曲线。票 04「池大小与 `timeout.scan.parallelism`」需要自己采这组数据。
- **生产库真实表清单**（30 张）只在探针 schema 上验证了「未声明即全挂」与「声明后全通」的**行为**，没在真实 `better_school` 库上跑过 30 张表的逐张点名（环境不允许动开发库）。行为由规则决定、与表名无关，故按已测结论处置；实现票在真库上跑一遍应作为验收项。

### 证据索引（本次会话新增，均在 `.scratch/timeout-inline/probe/`，属研究脚手架、不提交）

| 文件 | 内容 |
| --- | --- |
| `confirm2-out.txt` | **本轮权威输出**：C/D/E/F 四形态 × Q1–Q5 全矩阵（44 PASS / 30 FAIL，30 条 FAIL 全是「无 `!SINGLE` 时业务表不可用」的预期失败） |
| `gap-out.txt` | **待落配置的组合验证**：无 `databaseName` + `!SINGLE ["*.*.*"]`，6/6 通过 |
| `verify-out.txt` | `HintManager` 线程透传 5 场景界定 + 无 `!SINGLE` 的真实形态复现（20/20 业务表全挂） |
| `hintprobe2-out.txt` | hint 写分表 15/15 稳定通过（证明上一轮的「偶发」是探针缺陷） |
| `rollback-out.txt` | 事务/回滚独立复核：未提交行跨连接不可见、rollback 后 16 张分表全空 |
| `ss/ConfirmProbe2.java`、`GapProbe.java`、`VerifyProbe.java`、`HintProbe2.java`、`RollbackProbe.java` | 本轮探针源码 |
| `ss/probe-r-c/d/e/f/g/t.yaml` | 本轮 YAML 变体（每形态独立 schema + 独立 URL，规避 `DATA_SOURCE_CACHE`） |
| `confirm-out.txt`、`final-out.txt`、`named-out.txt`、`loader-out.txt`、`loader-bytecode.txt`、`probe-out.txt`、`confirm-info.txt` | 上一轮证据（结论仍有效，B 段的 hinted-write FAIL 已由本轮解释为探针缺陷） |
