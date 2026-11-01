# 票 03 · 预扣回读 create_time 并携带到消息

Type: task
Status: resolved

Blocked by: 01

## Question

让每条要发消息的预扣，把自己的时间格键（`create_time` 的秒值）带进消息。

范围：

1. `InventoryMergeMessage`：加 `LocalDateTime cellTime`（JSON 里就是 `2026-10-20T10:00:10` 这种无时区字符串）。**同步更新 javadoc**：载荷从「定位三字段」变成「定位三字段 + 责任起点秒」。
2. `IInventoryRepository.insertDeductDetail`：签名从 `void` 改成返回 `LocalDateTime`——在**同一个 `TransactionTemplate` 里**、INSERT 之后回读刚插入那行的 `create_time`。原因：`create_time` 由 DB 的 `NOW()` 写，应用事先不知道；应用时钟算出来会和 DB 差几毫秒。
3. 两张 mapper 各加一条 `selectDetailCreateTime`：
   - 虚拟 `WHERE inv_id=? AND biz_type=? AND biz_id=?`
   - 实物 `WHERE inv_id=? AND type=1 AND biz_type=? AND biz_id=?`
   两者都走业务唯一键（`uk_biz` / `uk_biz_type`），一行返回。
4. `InventoryQuotaServiceImpl.preDeduct`：`notifyMerge = true` 时用回读到的 `s` 作为 `cellTime` 构造消息；发送用**固定相对延迟** `Constants.MERGE_DELIVER_DELAY_MILLIS = 2000`（票 01 已定）。
   **不做时区转换**——相对延迟不需要把 `LocalDateTime` 转 epoch，`Asia/Shanghai` 那一坑整条消失。
5. `Constants`：加 `MERGE_DELIVER_DELAY_MILLIS = 2000`，注释写清推导：`s+1` 在 `s+2.0` 封口，相对延迟从发送时刻（≥ insert）起算天然带 0~1s 余量，broker 最多再晚 1s。

注意：撞业务唯一键（`DuplicateKeyException`）时事务回滚，回读不会执行，走既有的「回补 Redis + 按成功回放」分支——不要顺手改这段语义。

## Answer

### 1. 改了什么

| 位置 | 改动 |
| --- | --- |
| `InventoryMergeMessage` | 加 `LocalDateTime cellTime`；载荷口径从「定位三字段」改成「定位三字段 + 责任起点秒」；加 `withCellTime(s)`（返回新实例）与只带定位的三参构造 |
| `Constants` | 加 `MERGE_DELIVER_DELAY_MILLIS = 2000`（注释写全推导）；`INVENTORY_MERGE_DELAY_LEVEL` 保留但注明只剩续命在用 |
| `IVirtualInventoryQuotaDao` / `VirtualInventoryQuotaMapper.xml` | 加 `selectDetailCreateTime`：`WHERE inv_id / biz_type / biz_id`，走 `uk_biz` |
| `IPhysicalQuotaDao` / `PhysicalQuotaMapper.xml` | 加 `selectDetailCreateTime`：`WHERE inv_id / type / biz_type / biz_id`，走 `uk_biz_type` |
| `IInventoryRepository.insertDeductDetail` | 返回值 `void → LocalDateTime`（回读到的 `create_time`）；javadoc 补「回读 + 补 `cellTime` 同事务」 |
| `InventoryRepository` | `insertDeductDetail`：INSERT → 回读 → `message.withCellTime(s)` → `save(topic, msg, 0, 2000)`，四步同事务；私有 `queryDetailCreateTime` 两段分派 |
| `InventoryQuotaServiceImpl.preDeduct` | 接住回读值并打进日志（`cellTime=`），窗口去重/撞键回补那段语义一行没动 |
| 测试 | 新增 `unit.inventory.InventoryRepositoryTest`（6）+ `InventoryMergeMessageTest`（2）；`InventoryMergeTriggerIntegrationTest` / `PhysicalQuotaIntegrationTest` 的载荷与延迟断言改成新形状 |

### 2. 三个实现判断（票面没写死，写下来免得后面重新吵）

1. **`cellTime` 由仓储回填，不是域服务构造**。票面说「preDeduct 用回读到的 `s` 构造消息」，但消息必须与明细**同事务**落 outbox（否则只发消息不落明细就是消费侧空跑），而事务边界在仓储方法内、域服务不注入模板——回读到时间的那一刻已经在事务里了，域服务拿回来只能再开第二个事务，语义就散了。所以拆成：域层说「这一笔要叫醒哪个 key」（只带定位的消息），仓储在 INSERT 后把它补成完整载荷。`withCellTime` 返回新实例，不回写入参。
2. **兑换确认也一并回读**（票面只写了预扣，但不改就是留坑）。`cellTime` 是票 04 消费侧的必需字段（缺了就丢弃），确认消息若不带上它，兑换转移会全部掉进「必等 60s 兜底」。所以 `insertClaimDetail` 内部同样回读 `type = 2` 那一行的 `create_time`，并改走同一档 `2000ms`。`confirmIssued` 的域逻辑一行没动。
3. **实物 mapper 的回读用 `#{type}` 参数而不是写死 `type = 1`**：同一句 SQL 两处在用（预扣 1 / 确认 2），与既有 `existsDeductDetail` 同口径，调用方传 `Constants.DETAIL_TYPE_DEDUCT` / `DETAIL_TYPE_CLAIM`——SQL 语义与票面写死的 `type=1` 等价。

另外：回读的空值校验下沉在私有 `queryDetailCreateTime` 里（`Objects.requireNonNull`），两条路径共用同一个形状——读不到就抛、整笔回滚，宁可失败也不留一条 `cellTime` 为空、票 04 消费侧必然丢弃的唤醒消息。

### 3. 时区：整条不存在

`cellTime` 只用于定位格，**不参与延迟计算**；延迟是相对发送时刻的 `2000ms`。于是不需要把 `LocalDateTime` 转 epoch，`Asia/Shanghai` 一次都没出现。JSON 里就是 `2026-10-20T10:00:10`（无时区串）——Boot 的 `JacksonAutoConfiguration` 默认关掉 `WRITE_DATES_AS_TIMESTAMPS`，`JavaTimeModule` 也在，无需额外配置（单测按这一档断的形状）。

### 4. 撞唯一键的语义没动

`DuplicateKeyException` 在事务内抛出 → 回滚 → **回读那次 SQL 根本不会执行**、消息也不落；`preDeduct` 走既有的「回补 Redis + 删去重 key + 按成功回放」分支。单测 `insertDeductDetail_duplicateKey_neverReadsBackNorSends` 把这条钉住了。

### 5. 留给票 04 的已知空档

续命 `enqueueMergeMessage` 本票**没动**：它仍按 `delay_level = 1` 发只带定位三字段的载荷，所以那条消息的 `cellTime` 是 `null`。当前消费侧（`mergeKey`）不看这个字段，因此无害；**票 04 删掉续命 + 消费侧校验 `cellTime != null` 时，这段整段消失**，不要顺手给它补时间（续命要指向的是「还没封口的那一格」，不是某一笔的责任起点秒）。

### 6. 测试

- 单测 5 个（`marketing.unit.inventory`）：**只收外部看不出来的判据**——实物按 `type` 分派回读（预扣 1 / 确认 2，取错 type 会拿到另一行，同秒时值还可能一样）、回读为 null 时在落 outbox 之前失效（集成测试造不出这个状态）；外加消息载荷的形状 2 个（无时区秒串往返、`withCellTime` 不改入参）。「`cellTime` 等于明细 `create_time`」「`delay_ms = 2000`」这类能落到 MySQL 上看的判据一律留给集成测试（`backend/docs/testing.md` 的单测预算）。
- 集成：`InventoryMergeTriggerIntegrationTest` 断言 `cellTime` 逐字等于明细行 DB 写下的 `create_time`、`delay_ms = 2000` 且 `delay_level = 0`；`PhysicalQuotaIntegrationTest` 断言确认消息的 `cellTime` 取自 `type = 2` 那一行。
- 全量 `mvn -o test`：**134 tests 绿**（基线 129 + 本票 5）。

### 7. review 后修掉的三处

- 预扣那半边有 `requireNonNull`、确认那半边没有 → 下沉到 `queryDetailCreateTime`，两条路径一个形状（否则确认能落一条 `cellTime=null` 的消息，正好是本文档说要避免的）。
- 新增 javadoc 把「消费侧丢弃缺 `cellTime` 的载荷」写成了现在的事实——现在只有 `mergeKey`，它不看这个字段。改成「票 04 起的消费侧」，并点明续命那条旧载荷只能撑到票 04。
- `insertDeductDetail` 里同一个概念起了两个名字（`cellCreateTime` / `insertedCreateTime`）＋ 一个只为换名的外层局部变量 → 收敛成一个名字、直接返回事务结果。
