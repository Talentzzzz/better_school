# map：库存合并换成「格绑定工单」

Label: wayfinder:map

## Destination

库存合并换轨**落地并跑绿**：合并消息携带 `create_time`、用定时消息在「秒 s+1 封口后」投递、消费端固定合 `s` 与 `s+1` 两格、**删除续命**；`backend/marketing/` 代码改完、集成测试重写并通过、`inventory.md` / `CONTEXT.md` / 新 ADR 同步。到达终点的判据：每格固定 2 个消费者（1 干活 1 空转）、消息 1 条/秒/桶、延迟常量由**实测**得出而不是猜。

## Notes

- **本 effort 携带实施**（不只是出决策）：票 02 起都是改代码的 `task` 票，一票一做完再开下一票。
- **范围**：`backend/marketing/` 的库存域（`domain.inventory` + `infrastructure.inventory` + `trigger.inventory`）+ `outbox_message`。不动单体 `backend/better-school/`。
- **沿用 [ADR-0011](../../docs/adr/0011-inventory-merge-delay-message.md) 的表结构与索引，一条不改**：`idx_key_cell (inv_id, bucket_id, state, create_time)`、`idx_pending (state, inv_id, bucket_id, create_time)` 都与新方案兼容（`state` 必须在 `create_time` 之前，否则兜底的 `MIN` 从 O(log n) 退化 O(n)）。唯一 DDL 改动是 `outbox_message` 加 `delay_ms`（相对毫秒延迟）。
- **边界 1 已由用户拍板接受**：起点扣 Redis 与明细 INSERT 跨秒时，窗口前半段那一格可能无人指向，由 60s 兜底收口（概率 <0.01%）。**不要再提三格方案**（空转 50%→67%）。
- **边界 2 已由票 01 关掉**：broker 时间轮按秒槽对齐且「槽过完才排空」⇒ 只会晚、不会早，早到风险不成立；真机确认挪到票 05 的集成测试（断言 2.0~3.2s）。
- **延迟用相对毫秒、不用绝对时刻**（票 01 的关键简化）：`outbox_message.delay_ms = 2000`，消息载荷仍带 `cellTime` 供消费端定位格，但**不参与延迟计算**，因此不需要 `Asia/Shanghai` 转 epoch。
- **本轮讨论已经推翻过一次的结论，别再走回去**：
  - 「消息带 `create_time` 就够、不必带 `+1` 的第二条消息」——一条消息合两格即可，消息量减半。
  - 「延迟必须 2.2s」——只对 `s + 2000` 这个绝对基准成立；相对延迟 `now + 2000` 天然带 0~1s 余量。
  - 「只改延迟到 2s 就能去续命」——不能，还必须让消费端合到边界就停、不再查 `existsUnmergedDetail`。
  - 「按秒槽取整会提前 1 秒投递」——错。`currReadTimeMs >= currWriteTimeMs` 守卫保证槽过完才排空，方向是晚。
- 相关 skill：口径票 `grilling` + `domain-modeling`；事实票 `research`。

## Decisions so far

<!-- 索引：每个已关闭票一行；细节在该票 ## Answer 里 -->

- [票 01 · 验证定时消息通道与投递精度](issues/01-verify-timer-message.md): `RocketMQTemplate` 的毫秒级延迟**只有同步方法**（`syncSendDelayTimeMills` / `syncSendDeliverTimeMills`，无 async 变体）→ 异步必须下探 producer + `setDelayTimeMs`。broker 5.x 时间轮按秒槽对齐，且**槽过完才排空**（`currReadTimeMs >= currWriteTimeMs` 守卫）⇒ **只会晚 0~1s，不会早到**，边界 2 的早到风险不成立。**据此把方案从「绝对 `deliverTime(s+2000)`」改成「相对延迟 2000ms」**：时区转换整条消失，余量天然存在。参数定 `MERGE_DELIVER_DELAY_MILLIS = 2000`，实际合并延迟 2.0~3.0s。
- [票 02 · outbox 支持毫秒级相对延迟](issues/02-outbox-absolute-deliver-time.md): `outbox_message` 加 `delay_ms`，`save(...)` 四参重载（与 `delay_level` 互斥），`sendAsync` 三分支（`delayMs`→producer 直发定时消息 / `delayLevel`→五参 `asyncSend` / 否则两参），`scanPending` 只让 `delay_ms` 行失去定时。**实机确认 `setDelayTimeMs(2000)` + 异步发送落 2.28~3.03s、无一条早到**（对照 1s 档 1.07s、直发 60ms），票 01 的估计成立。量时延要先把 topic 建好、消费者起好——首拉 ~20s 路由退避会伪装成投递延迟。129 tests 绿。
- [票 03 · 预扣回读 create_time](issues/03-read-back-create-time.md): `insertDeductDetail` 改返回 `LocalDateTime`（INSERT → 回读 `create_time` → 补 `cellTime` → 落 outbox，四步同事务），消息载荷变「定位三字段 + 责任起点秒」，延迟改 `save(topic, msg, 0, 2000)`。`cellTime` **由仓储回填而不是域服务构造**（时间只有 DB 知道，且消息必须与明细同事务）；**兑换确认也一并回读**（否则票 04 消费侧丢载荷 → 确认全押 60s 兜底）。时区整条不存在：`cellTime` 不参与延迟计算。续命仍发无 `cellTime` 的旧载荷，等票 04 删。134 tests 绿。
- [票 04 · 消费侧固定合两格并删除续命](issues/04-consume-fixed-two-cells.md): 新增 `mergePair(segment, invId, bucketId, cellTime)` 固定合 `s` → `s+1` 两格（某一格 0 **不中断**另一格），消费侧只认带 `cellTime` 的载荷（缺了就 `log.error` 丢弃，**不**退化查 `MIN`——`mergeKey` 保留给兜底，两条路径不合并）；续命整段删除（`existsUnmergedDetail` + `enqueueMergeMessage` + `INVENTORY_MERGE_DELAY_LEVEL` 一并清掉）；`mergeCell` 的空转与竞态日志降 `debug`。固定两格下**第二格空转是结构性的**（一对格子被前后两条消息各指一次），所以 `debug` 不是权宜。133 tests 绿；docs/ADR 与集成测试重写留给票 06 / 05。
- [票 05 · 重写合并集成测试](issues/05-rewrite-merge-tests.md): 两个集成测试**整篇重写**成「一条消息合两格」：引擎类 8→12 条（两格合掉 / 第二格逐次空转 / 顺序反证 / 不续命 / 兜底收口 / 缺 `cellTime` 丢弃 / 受影响回滚 / 10 格上限 / key 选取），触发类补「`delay_ms=2000` + 扫描重发走两参 `asyncSend`」7 条。**边界 2 真机确认**：新增 `InventoryMergeDeliveryLatencyTest`（专用 9877 namesrv + 10921 broker，`-Dbs.inventory.mq-e2e=true` 才跑），端到端「发出 → 消费合完」三次实测 **2931 / 2993 / 2967 ms**，全部 ≥2000ms、落在 2.0~3.2s，票 01 的「只会晚不会早」成立。`InventoryMergeTestSupport` 行为不变、注释改成「只剩兜底能用」（它改 `create_time`，消息路径用了会假绿）。139 tests 绿 + 1 skipped（时延类默认关，跑法进 `docs/testing.md`）。
- [票 06 · ADR 与文档同步](issues/06-adr-and-docs.md): 新增 **ADR-0012**（消息带责任起点秒 + 2000ms 定时投递 + 固定合两格 + 删续命），代价四项写全（延迟 2~3s / 丢消息必等 60s / 50% 空转 / 滚动发布载荷兼容），边界 1 的接受记录独立成节，否决四案（保持现状、只改延迟、秒对齐窗口、三格）。ADR-0011 顶部加「合并触发 + 续命被 0012 superseded」（部分取代，不加 `status:`）。`inventory.md` 的「触发」「合并」「兑换确认」「与主链路相接」逐段换成新语义；`CONTEXT.md` 的**预扣 / 时间格合并 / 超时任务**三个词条改口。顺手修掉 `lottery.md`、`draw.md`、`timeout.md`、`sql.md` 里四处同一事实的旧说法（否则与「唯一事实源」自相矛盾）。零代码改动，未跑 test（票 05 的 139 绿仍是当前代码状态）。

## Not yet specified

<!-- 见「Fog of war」：还在雾里、尚不能成票的 in-scope 问题 -->

- ~~**滚动发布期间的载荷兼容**~~：已由票 04 收口——旧格式（无 `cellTime`）的载荷在消费侧 `log.error` + 丢弃，**不**退化查 `MIN`（否则「消息坏了」与「消息丢了」混成一种观测），切换窗口内的旧消息落到 60s 兜底。
- ~~**上线切换形态**~~：已由票 06 的 ADR-0012 定性——**不双跑**（新旧逻辑并存会让「这条消息该合哪格」有两种答案），按「先发能丢弃旧载荷的消费侧、再发带 `cellTime` 的生产侧」发布，切换窗口内的旧消息落兜底。发布编排本身不在本 effort 的代码范围内。
- **监控阈值**：`pendingDeductQty` 从「长期 > 0 报警」改成什么口径（新方案下正常稳态是 0~2 格在途）。等真实流量数据再定。

## Out of scope

<!-- 见「Out of scope」：被明确划到本 effort 终点之外的工作；已关闭，永不毕业 -->

- **broker 同步刷盘 + 主从同步**：消息不是事实源，丢消息只影响及时性（60s 兜底收口），可靠性预算花在 DB 上。已判定不需要。
- **窗口 TTL 对齐秒边界**（`1000 - now%1000`）：能让每格只有 1 个消费者、零空转，但 TTL 按 Redis 时钟算、`create_time` 按 MySQL 时钟写，两个时钟不同源会漏格——用可见的空转换静默的漏格，不划算。已否决。
- **消息带三格（`s-1, s, s+1`）**：为边界 1 的万分之一概率把空转从 50% 抬到 67%。已否决（边界 1 改为接受）。
- **`idx_key_cell` 追加 `type, quantity` 做覆盖索引**：与本次换轨无关的独立优化，且会让每次 `state` 翻转写的索引项变宽。留在本次之外。
