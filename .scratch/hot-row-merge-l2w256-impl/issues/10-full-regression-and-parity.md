# 10: 全量回归与两侧对等核验

**Spec:** `.scratch/hot-row-merge-l2w256/spec.md` §7、§8.1、§2.5；判据见 `acceptance.md` §2 全表与 §4 处置清单

**What to build:** 按 `acceptance.md` 逐条勾完三组断言（A 引擎 / B 库存守恒 / C 计数精确），并把 §4 的测试处置清单落到实际状态：3 个类整份退休、6 个类改写或整份重写、7 个类必须保持绿。这里是「两份实现漂移」的最后一道人工检查点——两侧合并器的同名 API、同名参数、同名用例必须逐项对齐。

红了的含义要分清：§4.3 里任何一条红，先怀疑实现（同事务形状写错 / 计数被缓存），不要改断言。

**Blocked by:** 09

**Status:** resolved

- [x] A1–A7 在两份实现里各有一份，用例名与清单逐字一致；两份的 `ShardBuffer` 方法名与 `MergeMetrics` 字段名逐项对照无差异
  —— bs `CountMergerTest` 与 marketing `QuotaMergeBufferTest` 的 A1–A6 用例名逐字一致；A7 两侧 `MergeConfigTest`（7 条）+ `CountMergeConfigTest` / `QuotaMergeConfigTest`（5 条）方法名逐字一致。`ShardBuffer` 两侧方法名逐项一致（offer / bringForward / scheduleNext / runFlush / takeBatch / failRemaining / failAllLocked / pendingSize / isIdle）；`MergeMetrics` 十字段两侧逐字一致。证据与库存侧四条特有出口用例见 [pr-description.md](../pr-description.md) A 组表
- [x] B1–B6 全绿，含「探一次再切」的部分成功用例与 `waiting` 不足整组回滚
  —— B1 `noOversellUnderConcurrency`、B2 `ledgerSumEqualsInitialSnapshot`、B3 `detailSumEqualsWaitingPlusIssued_partialSuccess`、B4 三条 replay + `PhysicalQuotaIntegrationTest` 三条、B5 `claimWaitingShortage_rollsBackWholeGroup`、B6 `confirmIssued_transfersImmediately` + `ship_physicalPrize_confirmTransfersImmediately`，随 marketing 全量 116 条全绿
- [x] C1–C6 全绿；C4 只断言单调不减 + 丢弃可读数，**没有**「浏览数 == 请求数」这类断言
  —— C4 `viewCountIsMonotonicAndDropIsCounted`（bs-app）逐行核对：只断言 `isGreaterThanOrEqualTo` 逐次不回退 + 排空后至少一次增量 + `droppedViews` 可读，注释明写「断言等于请求数就等于禁止丢弃（C4 明确否掉这条）」
- [x] §4.1 三个类整份退休（引擎 12 / 投递时延 1 / 触发 7）；§4.2 六个类改写到位；§4.3 七个类仍绿且**未改断言**
  —— 3 个退休类源码已不存在（仅 target/ 残留历史 surefire 报告）；6 个改写类逐类核对（`PostCountAggregationIntegrationTest` → `PostCountMergeIntegrationTest` 整份重写、`InventoryMergeTestSupport` 只剩 `awaitDrained / pendingOf`、`SwitchableInventoryBucketPort` 全仓无引用）；§4.3 全程 `git diff 5847e2c..HEAD` 仅 `CommentLikeIntegrationTest` +27 行（acceptance §2 C6 授权的新增「重复点赞不重复计」），其余 6 类零改动
- [x] D1–D3 的 SQL 与读数模板能在预发跑通（线上执行留给切换窗口），`timedOut == 0`、`BUFFER_FULL` 降级 0、`drainedFailed` 0 有对应读数位
  —— D1 / D2 两条 SQL 已在本地预发栈（`backend/docker` 的 bs-mysql，bs_marketing / better_school 两库）实际执行通过，无 `Unknown column` / `Table doesn't exist`，D1 返回 0 行；正式预发/线上执行留给切换窗口。D3 读数位在 `cutover-runbook.md` 第 8 步 + `cutover-record-template.md` §5（含 `timedOut` 非 0 时先看 `maxFlushNanos` vs `waitTimeoutMs` 的指引）
- [x] 两侧工程全量构建与测试绿；启动无 `Unknown column` / `Table doesn't exist` / 「找不到执行器任务」
  —— marketing `mvn -o test` 全 reactor **116 条 0 失败**；bs `mvn -o clean test -pl bs-app -am` 全 reactor **341 条 0 失败**（8 模块全 SUCCESS）。跑前先 `backend/scripts/test-env.sh reset-test-db` 重建为最终形态 schema。集成测试起完整 Spring 上下文（同一套启动配置），无列 / 表 / 执行器任务报错。过程记录：首轮 bs 全量时 Docker 宿机抖动打断测试栈（`bs-nacos-test` Exited 255），49 条全是 `publishSharedConfig` ConnectException 的环境故障，栈恢复重跑即绿——不是断言红，未改任何断言
- [x] review 清单成型并写进 PR 描述：合并器不得从 payload 推导 key；同一业务行所有调用点给同一个 `shardKey`；降级必须复用同一个落库方法；丢弃必须计数
  —— [pr-description.md](../pr-description.md)「Review 清单」四条，每条附两侧代码落点与核验结果
- [x] 未扫参声明保留在案：窗口 / 批 / 分片 / 线程全是推导值，后续只按合并比与 `timedOut` / `rejectedFull` 调参，**调参不改结构**
  —— bs / marketing 两份 `application.yml` 参数段均写明「上线后只按 MergeMetrics 的 flushed ÷ batches（合并比）、timedOut、rejectedFull 调，调参不改代码结构」；acceptance §5 与 pr-description「未扫参声明」一致
