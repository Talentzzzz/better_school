# 11 task：汇总产出 spec.md（effort 收口）

Type: task
Status: resolved
Blocked by: 05, 06, 07, 08, 09, 10

## Question

把票 05–10 的裁决汇总成 `.scratch/virtual-thread/spec.md`，即本 effort 的终点。spec 至少含：

1. **采用规则**——图绘期定稿的选点规则（并发上限由平台线程数决定 + 工作主体是阻塞 I/O）
2. **框架开关与承压口径**：4 工程的落地面、Hikari / 背压上限、pinning 处置（[票 05](05-grill-framework-flip-and-backpressure.md)）
3. **逐执行上下文裁决表**：上下文 / 裁决 / 理由 / 可回收触发条件（表结构由[票 10](10-grill-observability-and-acceptance.md) 定）——8 类上下文逐个有结论，含「不采用」的
4. **四节上下文详述**：消息消费（[票 06](06-grill-rocketmq-consumer-model.md)）、RPC（[票 07](07-grill-dubbo-thread-model.md)）、调度与批处理（[票 08](08-grill-scheduling-and-existing-fanout.md)）、长连接（[票 09](09-grill-websocket-fanout.md)）
5. **可观测与验收**（[票 10](10-grill-observability-and-acceptance.md)）
6. **按工程的适用性表**：4 个工程各自翻不翻、翻什么
7. **Out of scope**：照 [map](../map.md) 的 Out of scope 节

**不含**代码改造步骤——spec 定稿后另拆落地票（同目录，`Status: ready-for-agent`，照 timeout-center 惯例）。产出后此 effort 收口。

落稿过程中若发现有票的结论互相打架，**回对应票问**，不在本票里自行拍。

## Answer

（2026-09-21 收口。spec 已产出：[../spec.md](../spec.md)，七部分齐备——① 采用规则（含票 07 判读精化 + 全文「实测无收益 / 用户口径」诚实声明）；② 框架开关与承压口径（落地面表、两条硬约束、Dubbo 显式写值 `virtual`、背压 = 连接池 + `connection-timeout: 3000` 且三工程 Hikari 显式化记为**目标态**、pinning 四栏表只修 `ensureBucket`）；③ 逐执行上下文裁决表 **13 行**（照票 10 四列结构，8 类上下文全覆盖，含 7 行「不采用」按三段式）；④ 四节详述（消费 / RPC / 调度与批处理含模板两变体 + 四规矩 + caveat / 长连接）；⑤ 可观测与验收（命名 + JFR threshold=0ms + 替代信号手册 + 分路径平台线程数口径表 + 三 seam 断言 + 回归防线与禁令）；⑥ 按工程适用性表；⑦ Out of scope 照 map。）

**落稿过程中未发现票间打架**：票 05↔07 的「压回」反转、票 06→09 的倾向传递、票 08 对 05/03 的两处事实修正，均已在各自票内回填完毕，spec 照修正后的口径落稿，无需回票重问。代码锚点抽查核实（`FileStoragePort.java:77` / `WalletMapper.xml:43` / outbox `SCAN_BATCH_SIZE=100`）。

spec 末节留了落地票拆分倾向（按上下文聚簇），具体拆票另开——本 effort 到此收口。
