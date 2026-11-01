# 07: 工程骨架与任务 API（注册/取消）

**What to build:** 超时中心独立工程立起来，业务方能通过 Dubbo 注册/取消超时任务，任务行落进正确分表。新建仓库顶层工程 `timeout-center`（与 better-school 平级，仅 `timeout-center-api` + app 两模块，spec §2），技术栈对齐现仓库：Spring Boot 3.3.5 / Dubbo tri / Nacos / xxl-job 3.0.0 / ShardingSphere-JDBC 5.x / MyBatis / JDK 21 虚拟线程。独立库建 16 张分表 `timeout_task_00..15`（DDL spec §3.2）。实现 `ITimeoutTaskRegister`：批量信封入参 + 成功/失败双清单响应，中心侧虚拟线程逐行落库（spec §4.1），幂等四分支齐全——新插入 / 同键幂等成功不改期 / 终态复活（spec §3.4）/ 不同 group 冲突失败 + 告警。实现取消 API：软置 CANCELED（spec §4.2）。部署细节（端口/启动脚本/建表脚本落位）随本票定。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] Dubbo 调注册 API，新任务行落在 `hash(taskType+bizId)%16` 指向的正确分表（complex 分片算法路由验证）
- [x] 同 `(taskType, bizId)` 重注册（同 group）返回幂等成功，不改 action_time
- [x] 终态行重注册（同 group）触发复活：status 回 WAITING、action_time 更新、retry_count 清零
- [x] 同键不同 group 注册进失败清单 + 告警日志（冲突守卫）
- [x] 批量信封部分失败：失败项按 `(taskType, bizId)` 对应出现在失败清单
- [x] 取消 API 将 WAITING/RETRYING 行置 CANCELED
- [x] 端口/启动脚本/16 表建表脚本落位（spec §2 留给落地票的项随本票收口）

## Answer

2026-09-19 落地。骨架（七模块 / 独立库 16 分表 / Dubbo 注册与取消 API / 幂等四分支）此前已在，
本票收尾真正卡住的是 **ShardingSphere 5.5.x 的接入**——表现为「注册 API 返回空双清单、无异常」
（`registerOne` 逐行 try/catch 把连接失败吞成失败清单），一路往下挖出五处，前四处是 SS 接入本身：

1. **`shardingsphere-jdbc` 5.5.x 只是「驱动壳」，特性 artifact 要自己补**（缺哪个报哪个，且错误信息
   都不提「少依赖」）：补 `shardingsphere-sharding-core`（否则 `Invalid tag: !SHARDING`）、
   `shardingsphere-infra-data-source-pool-hikari`（否则 `StorageUnit` 里 `Map.get("url")` 返回 null 炸 NPE）、
   `shardingsphere-standalone-mode-repository-memory`（否则 `SPI-00001 … ContextManagerBuilder`）、
   `shardingsphere-authority-simple`、`shardingsphere-parser-sql-engine-{mysql,sql92}`。
   权威清单 = `shardingsphere-jdbc` 自己 pom 里那批 **test scope** 依赖（即作者跑测试的集合）。
2. **commons-lang3 覆盖到 3.18.0**：Spring Boot 3.3.5 管 3.14.0，sharding-core 用了 3.18.0 才有的
   `org.apache.commons.lang3.Strings`（不然 `NoClassDefFoundError`）。
3. **`TimeoutTaskShardSlots.tableNameOf` 少了下划线**：`TASK_LOGIC_TABLE` 是 `timeout_task`（无尾下划线），
   拼出来是 `timeout_task07`，与物理表 `timeout_task_07` 全不匹配 → 算法返回空集 → SS 退化成全 16 表广播，
   报 `Please check your sharding conditions … routing to multiple data nodes`。补 `"_"` 后修复。
   （集成测试的 `expectedShardTable` 是独立复算公式，所以这个 bug 一开始表现为「预期表没行」而非路由报错。）
4. **`timeout-center-api` 的 4 个 DTO 补 `implements Serializable`**：Dubbo 3.3 + hessian2 的类检查会拒非序列化类，
   首次真调即 `SerializationException`（`serialize-check-status: WARN` 只管类名 allowlist，救不了这个）。
5. **测试基类 `dateTime()` 兼容两种取值**：Connector/J 8.x 的 `getObject` 直接返回 `LocalDateTime`，
   不再是 `Timestamp`，原强转 `ClassCastException`。

完成线验证：`mvn -pl timeout-center-app -am test` **14/14 绿**（注册 8 + 取消 5 + Dubbo 往返 1）；
`scripts/test-env.sh up` 幂等通过，`bs_timeout` / `bs_timeout_test` 各 16 张分表。
顺手删了 `TimeoutTaskComplexShardingAlgorithm.shardingColumns()`（无引用）。

code-review 双轴后的收口（两处人工拍板 + 一处硬违反）：

- **infrastructure 包结构对齐 `backend/CLAUDE.md` §3**（原写成 `infrastructure/<域>/adapter/repository`，
  文档与 marketing 先例都是 `infrastructure/adapter/repository/<域>`）：`TimeoutTaskRepository` 移入
  `adapter/repository/task/`，`ITimeoutTaskDao` + `TimeoutTaskPO` 移入 `dao/task/`(`po/`)，同步 mapper XML 的
  `namespace`/`resultType`、`MybatisConfig` 的 `@MapperScan`、两个 profile 的 logging level。
  domain 层本来就合规范，未动。
- **`backend/docs/README.md` 未同步**（违反 CLAUDE.md §9）：补第四工程、补 `architecture/timeout-center.md` 行、
  补「改库表/接口后要同步的文档」一句。
- **checklist 第 7 条的「启动脚本」按「文档即启动说明」收口**（人工拍板）：仓库四个工程都没有启动脚本
  （`backend/scripts/` 下只有 `test-env.sh`），启动方式写在 `architecture/timeout-center.md` §2，
  不为单个工程新造脚本。

未处理（记为知情残留，不改）：`(taskType, bizId)` 仍以两个 String 穿过 domain/adapter seam；
`TaskStatus.isTerminal()` 与 mapper 里的 `status IN (...)` 字面量各自维护同一分区；`TimeoutTaskRegisterCase.cancel`
是纯转发（为与 `batchRegister` 保持层对称保留）。

给票 08 的备忘：扫描走 HintManager 时**必须复用 `TimeoutTaskShardSlots.slotOf` 的同一公式与 `tableNameOf`**
（后者刚修好，是「两算法同一编号空间」的落点）。上面 1、2 两条已记进
`backend/docs/architecture/timeout-center.md` §3（artifact 清单 + commons-lang3），4 记进同文件 §4。

## Comments
