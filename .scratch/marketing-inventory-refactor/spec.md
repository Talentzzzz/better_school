# spec：营销库存扣减重构（两套 quota + 时间格合并 + 延迟消息唤醒）

本文件是本次 effort 的终点产物，口径来源为 [票 01](issues/01-baseline-decisions.md)（grill 基线）、
[票 02](issues/02-rocketmq-delay-and-consumption.md)（RocketMQ 事实核查，全文 [research](research/rocketmq-delay-and-concurrency.md)）、
[票 03](issues/03-virtual-segment-merge-trigger.md)（虚拟段收口）、[票 04](issues/04-backstop-scan-scope.md)（兜底扫描）。
本 effort **只出 spec 与落地票，不写业务代码**；落地票在 [`.scratch/marketing-inventory-refactor-impl/`](../marketing-inventory-refactor-impl/map.md)。

范围：`backend/marketing/` 库存域（`marketing.domain.inventory`）及其与抽奖域的接线
（`IInventoryDeductPort` / `IQuotaConfirmPort`）、营销工程的共享 outbox 与 xxl 任务登记。
积分 / 次数 / 抽奖决策三域不动。

## 索引

| 节 | 状态 | 来源票 |
| --- | --- | --- |
| 1 目的地与边界 | 已定稿 | 01 |
| 2 表变更 | 已定稿 | 01 · 03 |
| 3 DDL 重建口径 | 已定稿 | 01 + 仓库既有约定 |
| 4 触发：1 秒延迟消息 + 60 秒兜底 | 已定稿 | 01 · 02 · 03 · 04 |
| 5 合并：时间格 + 最老一格 + affected 判重 | 已定稿 | 01 · 02 |
| 6 兑换确认（实物 `type = 2`） | 已定稿（票 05 明确为异步） | 01 · 03 |
| 7 孪生代码合并后的形状 | 已定稿 | 03 |
| 8 对账口径 | 已定稿 | 01 · 03 |
| 9 预热、故障与既有 bug | 已定稿 | 01 · 03 |
| 10 受影响的测试 | 已定稿 | 03 · 04 |
| 11 受影响的文档与 ADR | 已定稿 | 票面 + 票 05 |
| 12 已知波及与非目标 | 已定稿 | 票面 |
| 13 落地票索引 | 已定稿 | 票 05 |

**⚠️ 标记**：正文里两处 `⚠️ 票 05 补` 是写 spec 时才发现的缺口（不是对上游票的推翻），落地时按本文件执行；
§6 另有一处「票 05 明确」——票 01 的字面读法是异步确认，本文件把它定死。三处都记在
[map.md](map.md) 的 Decisions-so-far 里。

---

## 1 目的地与边界

- **两套 quota 并存**：只删「出入库」那对表（`physical_inventory` / `physical_inventory_detail`），
  `physical_marketing_quota*` 保留，虚拟那套 `virtual_inventory*` 改名 `virtual_inventory_quota*`。
  「所有库存都是 quota」= 两段用**同一套 quota 模型**（Redis 桶 + 预扣明细 + 时间格合并 + 兜底对账），
  **不是同一张表**。不要再理解成「并成一张表」或「删掉 physical_marketing_quota」。
- 两段的状态机：实物 `surplus → waiting → issued`；虚拟 `surplus → issued`（没有兑换确认）。
- 本次换掉的是**触发方式**（每秒全表扫 → 1 秒延迟消息唤醒 + 60 秒兜底），不是合并事务的骨架。
  说实话：两种方案都要为每条明细付一次扫描；收益是「单事务有界、消费者可并行、丢消息后能自愈」。
- 非目标（票 01 §7，不在本期，也不要顺手加）：分布式锁、认领态（`state = 2`）、`FOR UPDATE`、
  用 DB 唯一键做**消息**去重（唯一键只做业务幂等）、入库/出库流水表、多桶分摊/关桶/Redis 重建看门狗。

## 2 表变更

### 2.1 表收口

| 现在 | 之后 |
| --- | --- |
| `physical_inventory` / `physical_inventory_detail` | **删除** |
| `physical_marketing_quota` / `_bucket` / `_detail` | **保留**（实物：`surplus → waiting → issued`） |
| `virtual_inventory` | → `virtual_inventory_quota` |
| `virtual_inventory_bucket` | → `virtual_inventory_quota_bucket` |
| `virtual_inventory_detail` | → `virtual_inventory_quota_detail` |

命名不对称是有意的：实物侧叫「营销配额」（`physical_marketing_quota`，配额就是营销域的投放池），
虚拟侧叫「虚拟库存配额」（`virtual_inventory_quota`）；**不要**为了对称再改一次名。

### 2.2 两张明细表（统一形状）

虚拟：

```sql
CREATE TABLE IF NOT EXISTS virtual_inventory_quota_detail (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    inv_id      BIGINT      NOT NULL COMMENT '配额业务 ID',
    bucket_id   INT         NOT NULL COMMENT 'Redis 分桶编号（0 起）',
    quantity    BIGINT      NOT NULL DEFAULT 0 COMMENT '本次预扣数量',
    biz_type    VARCHAR(32) NOT NULL COMMENT '业务类型：LOTTERY_DRAW（抽奖预扣）',
    biz_id      BIGINT      NOT NULL COMMENT '业务 ID（抽奖 = draw_record.record_id）',
    state       TINYINT     NOT NULL DEFAULT 0 COMMENT '0-未合并 1-已合并',
    create_time DATETIME    NOT NULL COMMENT '插入时间（DB NOW()，秒精度）：合并的时间格键',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz (biz_type, biz_id),
    KEY idx_key_cell (inv_id, bucket_id, state, create_time),
    KEY idx_pending (state, inv_id, bucket_id, create_time),
    CONSTRAINT chk_virtual_quota_detail_state CHECK (state IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '虚拟库存配额扣减明细（Redis 桶 → 时间格合并的台账）';
```

实物：

```sql
CREATE TABLE IF NOT EXISTS physical_marketing_quota_detail (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    inv_id      BIGINT      NOT NULL COMMENT '配额业务 ID',
    bucket_id   INT         NOT NULL COMMENT 'Redis 分桶编号（0 起）',
    quantity    BIGINT      NOT NULL DEFAULT 0 COMMENT '本次流转数量',
    type        TINYINT     NOT NULL COMMENT '1-预扣结转（进 waiting）2-兑换确认（waiting → issued）',
    biz_type    VARCHAR(32) NOT NULL COMMENT '业务类型：LOTTERY_DRAW（预扣）/ LOTTERY_CLAIM（兑换确认）',
    biz_id      BIGINT      NOT NULL COMMENT '业务 ID（抽奖与兑换确认都用 draw_record.record_id）',
    state       TINYINT     NOT NULL DEFAULT 0 COMMENT '0-未合并 1-已合并',
    create_time DATETIME    NOT NULL COMMENT '插入时间（DB NOW()，秒精度）：合并的时间格键',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_type (type, biz_type, biz_id),
    KEY idx_key_cell (inv_id, bucket_id, state, create_time),
    KEY idx_pending (state, inv_id, bucket_id, create_time),
    CONSTRAINT chk_physical_quota_detail_state CHECK (state IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '实物营销配额流转明细（预扣结转 / 兑换确认两态，时间格合并的台账）';
```

- **`type` 只有实物有**：虚拟没有第二段流转，多一列只会多一个恒为 1 的死值。
- **实物唯一键必须带 `type`**：抽奖预扣行与兑换确认行共用 `biz_id = recordId`，只用 `(biz_type, biz_id)` 会撞键
  （票 01 §3）。**虚拟的唯一键只有 `(biz_type, biz_id)`**。
- **撞键 = 这条流转已落过**：不是错误，按「成功回放 + 释放本次 Redis 扣减」处理（见 §7.5）。
- **`create_time` 由 DB 的 `NOW()` 写**（INSERT 语句里写 `NOW()`，不用应用时钟），它是合并分格的唯一依据；
  不新增 token / 批次列。
- **`state` 收成 0/1 两态**：`2-已回收` / `3-失败` 从来没有任何写入方（全仓无写点），是死值。
  本次把列注释改成两态并加 `CHECK (state IN (0,1))`（MySQL 9 会真校验）；将来真要加「回收」态时
  再改注释 + 重建库，不预留空档。
- **索引两条，各司其职**（两张明细表各一对）：
  - `idx_key_cell (inv_id, bucket_id, state, create_time)`：延迟消息按 key 找最老的时间格（`MIN(create_time)`）与整格翻转；
  - `idx_pending (state, inv_id, bucket_id, create_time)`：兜底扫描是**全局** `state = 0`，`idx_key_cell` 的前缀是
    `inv_id` 用不上（票 04 §2）。旧 `idx_scan (inv_id, bucket_id, state, quantity)` 删除。
- **`quantity` 不再进索引**：合并按格读的是 `SUM(quantity) / COUNT(*) / type`，格内行数是个位数；
  不为覆盖查询再加列，保持上面两条索引的列与票面一致。

### 2.3 账本与桶表

- `physical_marketing_quota`：**列不动**。`surplus_qty` 在合并（`type = 1`）时递减，`waiting_qty` 在合并时加、
  兑换确认（`type = 2`）时转出，`issued_qty` 由 `type = 2` 加。`value` / `type` 只有虚拟有。
- `virtual_inventory_quota`（原 `virtual_inventory`）：**列不动**（`type` = `POINTS` / `DRAW_COUNT`，`value` = 单次面额）。
  `issued_qty` 在合并时直接加，`surplus_qty` 同步递减；**没有 `waiting_qty`**。
- `*_bucket` 两张：**列不动**（`redis_qty` 是对账副本）。`idx_key_cell` 的两条查询都不碰它，索引不用动。

### 2.4 `outbox_message` 加一列（延迟等级）

```sql
delay_level TINYINT NOT NULL DEFAULT 0 COMMENT 'RocketMQ 延迟等级；0=不延迟，1=1 秒（约 1.0–1.1s）'
```

- 加在**营销工程**的 `outbox_message`（`backend/marketing/sql/schema.sql`）；单体那份不动（单体没有延迟消息）。
- 为什么是列而不是「按 destination 查表」：扫描兜底重发（`selectPendingList`）是从 DB 读行再发的，
  延迟级别必须**随消息持久化**，否则重发的消息会立刻投递、把「1 秒后合并」变成「立刻合并」；
  一个共享 sender 里的 topic→延迟映射还会随配置漂移。

### 2.5 为什么 `physical_inventory*` 可以删

- `physical_inventory_detail` 的 OUT 行今天**没有任何生产读路径**（只有集成测试读）。
- `physical_inventory` 与 `physical_marketing_quota` 是两份同量账本；`confirmIssued` 各扣一次 `surplus_qty`，
  合并那步已经扣过配额，再扣库存就是双扣（票 01 §2）。
- 出库的单品追溯靠 `draw_record` + `quota.issued_qty`（总数）；真要「每次库存变动一行」时另立一张表（非目标）。

## 3 DDL 重建口径

`schema.sql` 是**唯一 DDL 源**，没有增量迁移（`backend/CLAUDE.md` §10：「不写增量迁移脚本，改表 = 改 schema.sql + 重建库」）。
本工程 `sql/migrations/` 已不存在，不要再建。

改表 = 一个动作三步：

1. 改 `backend/marketing/sql/schema.sql`（同时改根目录 `sql.md` §2，二者由
   `.scratch/project-rework/cmp_schema_marketing.py` 逐表比对，必须跑通）；
2. **重建开发库 `bs_marketing` 与测试库 `bs_marketing_test`**（表改名 / 删表不会自动跟上）；
3. 重跑 `inventoryBucketWarmup`（Redis key 前缀变了，见 §7.3；旧 key 不删也无害，新 key 由预热写入）。

测试库重建（Windows 本机手工等价，`scripts/test-env.sh reset-test-db` 在 bash 环境可用，本机 bash 是坏的）：

```powershell
docker exec bs-mysql mysql -uroot -pbs123456 -e "DROP DATABASE IF EXISTS bs_marketing_test; CREATE DATABASE bs_marketing_test CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
cmd /c type backend\marketing\sql\schema.sql | docker exec -i bs-mysql mysql -uroot -pbs123456 --default-character-set=utf8mb4 bs_marketing_test
```

**测试库不重建 = 假绿**：旧库里 `virtual_inventory*` 还在、新表不存在，`TRUNCATE` 与断言会打到错表；
更隐蔽的是「漏建索引」这类只影响性能/并发行为的差异，测试全绿但线上必炸。落地票 01 的第一件事就是重建。

**存量数据不迁移**：本仓无生产数据，dev / test 库直接重建；不要在 schema.sql 里写 `RENAME TABLE` 或
`ALTER` 守卫（那是迁移脚本的活，已明令不做）。

## 4 触发：1 秒延迟消息 + 60 秒兜底

### 4.1 topic / 消费组 / 载荷

| 项 | 值 | 落点 |
| --- | --- | --- |
| topic | `bs-inventory-merge` | `types.inventory.Constants`（生产方与消费方同域，域自有常量） |
| 消费组 | `bs-marketing-inventory-merge-group` | `types.inventory.InventoryMqConstants` |
| 载荷 | `{"segment":"quota\|virtual","invId":123,"bucketId":0}` | `types.inventory.InventoryMergeMessage` |

- 命名口径 `bs-<service>-<topic>-group`（[marketing spec §事件边契约](../marketing/spec.md)）：service = `marketing`，
  topic 段 = `inventory-merge`（去掉 `bs-` 前缀）⇒ `bs-marketing-inventory-merge-group`。
  不照 `bs-raffle-shipping-group` 那个历史例外改写，也不要起 `bs-inventory-quota-merge` 之类的新风格。
- 载荷**只带定位三字段**，不带数量、不带时间：数量由明细表的事实回答，时间由 `create_time` 回答。
  消费侧解析失败 = log + 丢弃（重试也不会成功）；`segment` 未知 = log + 丢弃（同上）。
- 生产方和消费方在同一个服务进程里（marketing-app），但**仍走 MQ 而不是 Spring 事件**：
  要的是「跨进程、可重投、可观测」以及和 outbox 的至少一次语义，不是省一次序列化。

### 4.2 预扣：Lua 里「扣减 + 决定要不要发」

`lua/inventory_bucket_deduct.lua` 改成两键两参：

```lua
-- KEYS[1] 桶 key；KEYS[2] 去重 key；ARGV[1] 数量；ARGV[2] 去重窗口毫秒
-- 返回 {code, notify}：code 0=扣成功 / -1=桶未预热 / -2=余量不足；notify 1=要发合并消息
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then return {-1, 0} end
local quantity = tonumber(ARGV[1])
if stock < quantity then return {-2, 0} end
redis.call('DECRBY', KEYS[1], quantity)
local notify = redis.call('SET', KEYS[2], '1', 'PX', ARGV[2], 'NX')
return {0, notify and 1 or 0}
```

- Redisson 侧用 `RScript.ReturnType.MULTI` 取回 `List<Long>`；端口返回
  `DeductAttemptVO(boolean deducted, boolean notifyMerge)`。`-1`（未预热）与 `-2`（余量不足）都按「扣不成」返回
  `deducted = false`（与今天一致：未预热也是业务事实）；Lua **无返回** 才是 Redis 故障，抛
  `BucketUnavailableException`（与今天一致）。
- **只扣成功才设去重 key**：扣失败的明细不存在，发了也是空跑。
- **去重语义是 throttle（固定窗口，从第一笔算），不是 debounce**：`SET NX PX 1000` 一旦写下，
  窗口内后续扣减一律 `notify = 0`，把唤醒交给第一笔；key **不在合并时删除**，只靠 TTL 过期。
- **多键同脚本**：今天 Redis 是单节点（`redis:8.8.0`，非 cluster），两键无同槽要求；将来若上 cluster，
  两个 key 要加同一个 hash tag（`{invId}`）——写进代码注释，不写进本期实现。
- 拿到 `notifyMerge = true` 才在**同一个事务**里「插明细 + 插 outbox」；`notify = 0` 只插明细。
  **Redis 已扣但明细没落上**：回补余额（`INCRBY` 等量）并**删掉去重 key**，再抛。
  ⚠️ 票 05 补：删 key 是为了让窗口里**还活着**的后续扣减重新发消息——赢家事务失败时，
  若 key 还在，整个窗口都会被吞，只能等 60s 兜底。

### 4.3 延迟级别与 outbox

- 延迟级别 = **1**（`Message.setDelayTimeLevel(1)`，broker 默认级别表 1s，实测 ≈1.0–1.1s）。
  5.x 的任意时间投递只有**同步**发送暴露（`syncSendDelayTime*`），异步链路唯一现成方案就是级别 1（票 02）。
- 异步加延迟只能走 `asyncSend(String, Message<?>, SendCallback, long timeout, int delayLevel)` 五参重载：
  `MessageBuilder.withPayload(payload).build()` 之后再发（没有 Object payload 的延迟重载）。
- **`OutboxMessageStore.save(destination, payload, delayLevel)`** 写 `delay_level`；
  **`OutboxMessageSender.sendAsync(OutboxPO)`** 读 `po.getDelayLevel()` 走五参重载。
  默认 0，只有库存合并 topic 传 1——**共享 sender 不能把 points / raffle 的事件也拖慢 1 秒**。
- 扫描重发（`outboxScan`）沿用同一列：库存合并消息会晚约 1s 变 SENT，可接受（票 02 §落点）。
- **重投不保留延迟**：listener 抛异常 → broker 用 `3 + reconsumeTimes` 重设级别，第一次重投是 **10s 后**，
  阶梯 10s / 30s / 1m … 16 次后进 `%DLQ%`。所以**不能把重投当快速恢复手段**，兜底扫描是必需的（§4.6）。

### 4.4 窗口尾巴：被吞掉的那笔谁来唤醒

⚠️ 票 05 补（写 spec 时才发现的口径洞）：throttle 的窗口是「从第一笔算的 1000ms」，消息到达是
「第一笔后 ≈1.0–1.1s」。若窗口内最后一笔的 `create_time` 恰好落在消息**到达的同一秒**，
这一格还没封口（`create_time < NOW()` 不成立），而它自己的消息又被 throttle 吞了 ⇒ 只剩 60s 兜底。
低流量 key 上这不是极端情况（两笔相隔半秒就可能命中），实物档会让「抽奖后立刻领奖」的确认多等一分钟。

**补法（合并侧的自我续命，不依赖 60s 兜底）**：一次唤醒把能合的都合完之后，
**若该 key 还有 `state = 0` 的行**（无论是超了 10 格上限，还是只剩当前这一秒没封口），
就**再给自己入队一条同 key 的合并消息**（延迟级别 1，走 outbox，同一套 `delay_level`）。
下一跳到达时那一秒已经封口，于是合掉、行清零、不再续命——**收敛**。

- 只在「还有未合行」时续命：正常路径（合完即空）一条都不多发。
- 重复投递无害：同 key 并发由 `affected == cnt` 判重（§5）。
- **不变量破坏时不续命**（§5.5）：那种格子重试也不会好，续命只会变成 1 秒一次的日志风暴。

### 4.5 消费侧

- 新监听器 `trigger/inventory/listener/InventoryMergeConsumer`：
  `@RocketMQMessageListener(topic = Constants.INVENTORY_MERGE_TOPIC, consumerGroup = InventoryMqConstants.INVENTORY_MERGE_CONSUMER_GROUP)`，
  解析后调 `IInventoryMergeService.mergeKey(segment, invId, bucketId)`。
- **业务异常不吞**：抛出让 broker 重投（10s 起步）；`mergeKey` 对「这一格合不了」（竞态 / 不变量破坏，见 §5.5）
  返回 `false` 而不抛，所以不会把一条坏格子刷成 DLQ 噪声。
- **同 key 可并行消费**（票 02 查实：默认 `CONCURRENTLY`、无队列锁；本仓还把消费投给虚拟线程池，
  并发上限只剩每队列 `pullThresholdForQueue = 3`）。合并不依赖串行，依赖 DB 层的 `affected == cnt`；
  **不要**为了「同 key 串行」去动发送队列或消费模式。
- 消费线程模型沿用现有 `VirtualThreadConsumeMessageConcurrentlyService`（注册器按注解全量接管，无需改动）。

### 4.6 兜底扫描（票 04）

- 新 xxl handler **`inventoryMergeBackstop`**，`0 * * * * ?`（60s）。票 04 说「与 outbox 扫描同频」是近似说法
  （种子脚本里 outboxScan 是 `0/30`）；**以 60s 为准**，最坏口径是「丢一条消息 → 60s 后发现」。
- 每轮按段各取 200 个 key，按最老未合时间升序：

  ```sql
  SELECT inv_id, bucket_id
    FROM <seg>_detail
   WHERE state = 0
   GROUP BY inv_id, bucket_id
   ORDER BY MIN(create_time)
   LIMIT 200;
  ```

  这条走 `idx_pending`（`state` 前缀 + 前向扫描，`GROUP BY` 与 `MIN` 都在索引里、不回表）；`LIMIT 200`
  是**单轮封顶**，不省扫描成本（同 ADR-0005 的「保险丝」口径：正常流量 pending 只有数十组）。

- 逐 key **直接调 `IInventoryMergeService.mergeKey(...)`**，不发消息——发消息要多一次 outbox 写入和一个
  「延迟 0」的特例，而并发已经由 `affected` 判重扛住。
- 分工：消费者负责积压（一次唤醒最多 10 格 + 自我续命），兜底**只负责消息整条丢了**——
  outbox 进 DEAD、Redis 扣了但事务失败、进程在两步之间崩、以及上面那条极窄的窗口尾巴。

## 5 合并：时间格 + 最老一格 + affected 判重

### 5.1 为什么用 `create_time` 当格键

`create_time` 是 INSERT 时 DB 写的 `NOW()`（秒精度，DATETIME 无小数）。用秒格而不是「批次令牌」：
不新增列、不增加每行写入、不需要额外的认领态；「过完的秒不会再长新行」这条性质就够用。

### 5.2 单格事务（实物；虚拟是它的删减版）

```sql
-- ① 该 key 的最老一格：只取已封口的秒
SELECT MIN(create_time)
  FROM physical_marketing_quota_detail
 WHERE inv_id = ? AND bucket_id = ? AND state = 0 AND create_time < NOW();

-- ② 该格的精确聚合（翻转前读，同一事务）
SELECT type, SUM(quantity) AS qty, COUNT(*) AS cnt
  FROM physical_marketing_quota_detail
 WHERE inv_id = ? AND bucket_id = ? AND state = 0 AND create_time = ?
 GROUP BY type;
-- s1 / c1 = type 1 的 qty / cnt；s2 / c2 = type 2 的 qty / cnt（缺哪组就是 0）

-- ③ 翻转（先翻转 = 先抢单；affected 必须等于 c1 + c2，否则整格回滚）
UPDATE physical_marketing_quota_detail
   SET state = 1, update_time = NOW()
 WHERE inv_id = ? AND bucket_id = ? AND state = 0 AND create_time = ?;

-- ④ 桶对账副本递减（只对 s1；s1 = 0 整条跳过）；0 行 = 不变量破坏 → 整格回滚
UPDATE physical_marketing_quota_bucket
   SET redis_qty = redis_qty - ?s1, update_time = NOW()
 WHERE inv_id = ? AND bucket_id = ? AND redis_qty >= ?s1;

-- ⑤ 台账结算：type=1 进 waiting（s1 = 0 跳过）
--    注意 state 的 IF 必须排在 surplus_qty 赋值之前（MySQL 的 SET 从左到右用新值）
UPDATE physical_marketing_quota
   SET state = IF(surplus_qty >= 0 AND surplus_qty = ?s1 AND state = 'open', 'exhausted', state),
       waiting_qty = waiting_qty + ?s1,
       surplus_qty = IF(surplus_qty < 0, surplus_qty, surplus_qty - ?s1),
       update_time = NOW()
 WHERE inv_id = ?;

-- ⑥ type=2 从 waiting 转 issued（s2 = 0 跳过）；0 行 = 不变量破坏 → 整格回滚
UPDATE physical_marketing_quota
   SET waiting_qty = waiting_qty - ?s2, issued_qty = issued_qty + ?s2, update_time = NOW()
 WHERE inv_id = ? AND waiting_qty >= ?s2;
```

虚拟段只有 ①②③④⑤：`SELECT SUM(quantity), COUNT(*)`、翻转、桶守卫递减、`+issued_qty`（`surplus_qty` 同步递减、
减到 0 置 `exhausted`），**没有 `type`、没有 ⑥**。

- ②③④⑤⑥ 必须在**一个事务**里（仓储方法内的 `TransactionTemplate`，`REQUIRED`）；一格一个事务。
- `affected != cnt` 时在仓储内抛 → 整格回滚 → `mergeCell` 返回 `false`（**不往上抛**）：这一格没被我们抢到
  （并发消费者先翻了，或有行在 SELECT 之后才提交）。下一跳/兜底会重来，账不会错。
- 翻转**按格条件**而不是按 id 清单：秒已封口 ⇒ 这一格不会再长新行 ⇒ 条件翻转与 id 清单等价，
  且省掉把几百个 id 拉回应用（票 01 §5 被否掉的「id 清单翻转」）。
- **结算分叉只看「明细行落在哪张表 + 行内 `type`」，不看 `draw_record.award_type`**：
  `award_type` 只在预扣选表时用一次（`PHYSICAL` → 实物配额表、`VIRTUAL` → 虚拟表、`THANKS` → 不扣）；
  实物配额不足改发兜底奖品时，扣的是**兜底奖品的池子**、行落在虚拟表，记录里的 `award_type` 也随实际发放的
  奖品改成 `VIRTUAL`——账、行、记录三者一致。

### 5.3 一次唤醒的排干循环

```text
合并最多 10 格（每格一个事务）：
  t0 = 该 key 的最老未合且已封口的秒（§5.2 ①）
  t0 为空 → 结束循环
  合这一格；失败（affected 对不上 / 守卫不过）→ 记日志，**结束**（不续命，交给兜底）
合完之后：若该 key 还有 state = 0 的行 → 入队一条同 key 的合并消息（§4.4 续命）
```

- 上限 10（票 01）：一次唤醒合多格是为了「丢一条消息不致永久落后一格」；再多就把单次消费拖长，
  剩下的交给续命/兜底。
- **只合最老一格**：`MIN` 保证同一 key 上 `type = 1` 永远不落到 `type = 2` 后面——丢消息、乱序、晚提交
  都靠它自愈。**这是 `type = 2` 的 `waiting_qty` 守卫能成立的前提，不要改成并行合多格。**

### 5.4 与本仓库既有实现的差异（落地时按本文件改）

| 现在 | 之后 |
| --- | --- |
| `inventoryMerge` 每秒扫全表 `state = 0`（无 LIMIT）→ 内存按 `(inv_id, bucket_id)` 分组 → 每组一事务 | 消息/兜底按 `(segment, inv_id, bucket_id)` 调 `mergeKey`；扫的是**一个 key 的最老一格** |
| 翻转按「本组携带的 id 清单」（`WHERE id IN (...)`） | 翻转按「`create_time = t0 AND state = 0`」+ `affected == cnt` |
| 合并任务不区分明细类型 | 实物按 `type` 分组：`s1` 进 waiting、`s2` 转 issued |
| 无「消息丢了自己再叫一次」 | 10 格上限 + §4.4 续命 |
| xxl `inventoryMerge` 每秒 | **删除**；换成 `inventoryMergeBackstop` 60s |

### 5.5 失败与不变量

三种「这一格合不了」都走同一条处置：**整格回滚 → 不动台账 → `mergeKey` 返回 `false`（不抛、不续命）**。
重试不会让账变错，所以只留给兜底扫描反复撞 + 对账读数报警，等人看：

| 触发 | 含义 | 处置与暴露口径 |
| --- | --- | --- |
| `affected != cnt` | 这一格没被我们抢到，或有行在聚合之后才提交 | `log.info`（正常竞态）；**唯一会自愈的一种**——该行自己的消息若还在，下一跳就合掉；否则等兜底 |
| 桶守卫 `redis_qty >= s1` 不过 | 台账不变量被破坏（Redis 漂移 / 手工改数据） | `log.error`；明细停在 `state=0`，`pendingDeductQty` 长期 > 0 |
| `waiting_qty >= s2` 不过 | 兑换确认找不到预扣结转（数据不一致或顺序被破坏） | `log.error`；明细停在 `state=0`，`pendingClaimQty` 长期 > 0 |

- **不抛**：抛会给 broker 一条注定失败的消息，刷 DLQ 噪声；合并不变量不是重试能解决的。
- **不续命**：续命是给「还有正常行没合」用的，坏格子每 1 秒续一次 = 日志风暴。
- 兜底扫描每 60s 会再撞一次同一个格子（同一处置），这就是「持续的告警信号」本身。

### 5.6 被否的替代（票 01 §5 + 票 02 修正，别再翻）

id 清单翻转（要把几百行拉回应用）、不带秒边界的 `WHERE state = 0` 翻转（快照读 vs 当前读对不上 `cnt`）、
`id <= MAX(id)`（分配序 ≠ 提交序）、认领态 / 批次令牌（每行多写一次，还要多一列）、分布式锁
（挡不住并发 insert，且让 Redis 成为合并的可用性前置）——全部在有「时间格 + 最老一格 + `affected` 判重」后失去必要性。

## 6 兑换确认（实物 `type = 2`）

**口径（票 01 §2/§3/§4/§5 合起来读）**：`confirmIssued` 不再同步做 `waiting_qty → issued_qty`，
而是**落一条 `type = 2` 的明细 + 每条都发一条合并消息**，转移由合并按时间格完成。

- **只对实物**：`segment = quota` 才有这个动作；虚拟没有第二段流转（`issued_qty` 在合并时直接加）。
- **不再动 `surplus_qty`**（合并那步已经扣过，再扣就是双扣）、**不再写出库行**（`physical_inventory_detail` 已删）。
- **bucket 从预扣行取**：`SELECT bucket_id FROM physical_marketing_quota_detail
  WHERE type = 1 AND biz_type = ? AND biz_id = ?`。不新增 `draw_record.bucket_id` 列、不让端口回传 bucket。
  取不到 = 数据不一致（这笔记录没有预扣结转行）→ 抛 `INVENTORY_PARAM_ERROR`，不落行、不发消息。
- **排序由 MIN 保证**：预扣行（`type = 1`）的 `create_time` 早于确认行（`type = 2`），同一 key 上先合 type=1，
  所以合并里的 `waiting_qty >= s2` 守卫恒成立；守卫不过 = 不变量破坏（不是业务失败）。
- **不走 SETNX，每条都发**：兑换低频，走去重会被窗口吞掉、押到 60s 兜底（票 01 §4）。
  幂等靠明细表的 `UNIQUE (type, biz_type, biz_id)`：同一条记录重复确认 → 撞键 → 当成功回放，**不再发消息**。
- **`4312 INVENTORY_WAITING_SHORTAGE` 退休**：同步确认没了，这个码再没有调用方（前端也从未见过它）。
  落地时从 `ResponseCodeEnum` 删除，并同步 `docs/api/lottery.md` 的错误码表与「发货消费的重投口径」一节。
- **`RaffleShippingCase` 的三步顺序保留**（先确认库存、再推进状态）：确认已变成一次本地写，
  顺序不再承重，但保留它没有代价，且「记录仍在待兑换」仍是抽奖域自己的幂等判据。
- 消费侧不再有「确认失败 → 重投 → 10s 后成功」的剧情；真正的失败只在合并侧表现为
  `pendingClaimQty > 0`（对账可见）+ 不变量日志。

## 7 孪生代码合并后的形状

目标：**一条路径 + 一个 `segment` 参数**。两套表、两套 PO/DAO/mapper 保留（表就是两张），
但 service / 仓储 / warmup / audit 里不再有「虚拟版方法 + 实物版方法」两份。

### 7.1 接口清单（before → after）

| 现在 | 之后 |
| --- | --- |
| `IVirtualInventoryService` + `IPhysicalQuotaService`（两个孪生） | **一个** `IInventoryQuotaService` |
| `VirtualInventoryServiceImpl` + `PhysicalQuotaServiceImpl` | `InventoryQuotaServiceImpl` |
| `IInventoryMergeService.mergeOnce()`（一次扫两段） | `mergeKey(segment, invId, bucketId)` + `backstopOnce()` |
| `IInventoryWarmupService.warmupVirtual/warmupPhysical/warmupAll` | `warmup(segment, invId)` + `warmupAll()` |
| `IInventoryAuditService.auditVirtual/auditPhysical` | `audit(segment, invId)` |
| `IInventoryRepository`（虚拟半 + 实物半，方法名各带 `Virtual`/`Quota` 限定） | 同一接口，**每个方法带 `segment` 参数**；方法数减半 |
| `IInventoryBucketPort.tryDeduct(key, qty) : boolean` | `tryDeduct(bucketKey, mergeDedupeKey, qty) : DeductAttemptVO` |
| `IInventoryBucketPort.release(key, qty)` | `release(bucketKey, mergeDedupeKey, qty)`（回补 + 删去重 key） |

`IInventoryQuotaService`：

```java
/** 预扣：查台账（不存在→INVENTORY_PARAM_ERROR；非 open→false）→ 逐桶 Lua（扣 + 去重）→ 同事务落明细（+ 可选 outbox） */
boolean preDeduct(InventorySegmentVO segment, long invId, long quantity, String bizType, long bizId);

/** 主档扣不出 → 换个 inv_id（可换段）再扣；两段都扣不出 = FAILED */
DeductOutcomeEnum preDeductWithFallback(InventoryDeductRequestVO primary, InventoryDeductRequestVO fallback);

/** 兑换确认（仅 quota 段）：落 type=2 明细 + 每条都发合并消息；转移由合并做 */
void confirmIssued(long invId, String bizType, long bizId, long quantity);
```

- `InventorySegmentVO`：`QUOTA("quota")` / `VIRTUAL("virtual")`，带 `code`；消息、Redis key、日志都用 `code`。
- `InventoryDeductRequestVO(segment, invId, quantity, bizType, bizId)`：把五个散参收成一个值对象。
- `bizType` 取值收在 `types.inventory.InventoryBizTypes`：`LOTTERY_DRAW`（预扣）/ `LOTTERY_CLAIM`（兑换确认）。
  抽奖域的两个端口方法**只透传 `recordId`**，`bizType` 由库存侧的端口实现填——抽奖域不认识库存的表列。
- `mergeKey` 返回 `MergeKeyResultVO(int cells, long details)`；`backstopOnce` 返回
  `MergeBackstopResultVO(int quotaKeys, int virtualKeys, long details)`（给 job 日志与测试断言）。

### 7.2 `preDeductWithFallback` 的新形状

```text
preDeduct(QUOTA, invId, qty, DRAW, recordId) 成功 → DEDUCTED
  失败 → fallback == null → FAILED
        → preDeduct(VIRTUAL, fallbackInvId, qty, DRAW, recordId) 成功 → FALLBACK
          失败 → FAILED
```

- 退化成「主档扣不出 → 换个 inv_id 再扣」；**配额段零写入**由「兜底路径根本不碰配额仓储」保证
  （与今天同一条论证）。段可以不同（实物配额 → 虚拟权益），这正是 fallback 的语义。
- 兜底奖品若本身 `inv_id = 0`（不限量），抽奖域根本不调库存（不落明细、不发消息）——抽奖域自己的判据，
  不改。

### 7.3 Redis key 前缀收敛

| 现在 | 之后 |
| --- | --- |
| `bs:inventory:virtual:bucket:{invId}:{bucketId}` | `bs:inventory:bucket:virtual:{invId}:{bucketId}` |
| `bs:inventory:quota:bucket:{invId}:{bucketId}` | `bs:inventory:bucket:quota:{invId}:{bucketId}` |
| —（新增） | `bs:inventory:merge:quota:{invId}:{bucketId}` / `bs:inventory:merge:virtual:{invId}:{bucketId}` |

- 常量收在 `types.inventory.Constants`：`BUCKET_KEY_PREFIX = "bs:inventory:bucket:"`、
  `MERGE_DEDUPE_KEY_PREFIX = "bs:inventory:merge:"`，两个组装方法 `bucketKey(segment, invId, bucketId)` /
  `mergeDedupeKey(segment, invId, bucketId)`（**不叫 `mergeKey`**：那是 `IInventoryMergeService` 的方法名）。
- **改前缀 = 必须重跑 `inventoryBucketWarmup`**；旧前缀的 key 留着无害（没人读），但别指望它们生效。
- 测试清理仍是 `bs:inventory:*` 一把删（覆盖新旧）。

### 7.4 仓储与事务落位

- `IInventoryRepository` 仍是**一域一仓储**，方法按 `segment` 分派到两个 DAO（`IVirtualInventoryQuotaDao` /
  `IPhysicalQuotaDao`）；**PO / DAO / mapper XML 不合并**（表就是两张，合了只是把 switch 挪个地方）。
- 类名跟表名：`VirtualInventory*PO` → `VirtualInventoryQuota*PO`；实体同理。
- 事务（`TransactionTemplate`，`REQUIRED`）只在仓储里，域服务不注入：
  - `insertDeductDetail(detail, mergeMessageOrNull)`：明细 + outbox 一个事务（0 或 1 条消息）；
  - `insertClaimDetail(claim, mergeMessage)`：`type = 2` 明细 + outbox 一个事务；
  - `mergeCell(segment, invId, bucketId, t0)`：§5.2 的整格（含聚合读、翻转、桶守卫、结算）；
  - `enqueueMergeMessage(segment, invId, bucketId)`：续命用的单条 outbox 插入（一个事务）。
- outbox 走共享的 `OutboxMessageStore`（`marketing-infrastructure/common/outbox`），不另起一套。

### 7.5 幂等与既有 bug

- **预扣行撞键**（`(type,) biz_type, biz_id`）：这条流转已落过 → `release(bucketKey, mergeDedupeKey, qty)` →
  **返回 true（按成功回放）**。抽奖随后落 `draw_record`，若也撞 `uk_record_id` 就回放原记录。
- **顺带修掉的既有 bug**：预扣在 `draw_record` 事务之外，同一 token 双击会让两个请求各扣一次 Redis、
  各插一条明细（旧表没有业务唯一键，另一份没人退）。加了明细唯一键后，第二笔撞键 → 释放 + 回放，
  净扣减只有一份。虚拟段同病同修。
- **兑换确认撞键**：同一条记录重复确认 → 当成功返回（转移由第一笔的格完成，重复的格不会再入队）。
- **消息重复**：`affected == cnt` 判重 + 每格先翻转；重复消息/重复消费都是空跑，不会二次记账。

## 8 对账口径

**统一成一条恒等式**（两段同一个表达，差别只在 `waiting` 是否恒 0）：

```text
ledger.waiting_qty + ledger.issued_qty == SUM(quantity) WHERE state = 1 AND type = 1   -- 实物
ledger.issued_qty                      == SUM(quantity) WHERE state = 1                 -- 虚拟（waiting 恒 0，无 type）
```

- 实物 `type = 2` **不进这条等式**：它在两个列之间搬（`waiting -s2 / issued +s2`），和不变。
  所以「只算 `type = 1`」既对，又能把「合并停跑」与「确认没落地」分开看。
- **Redis 恒等式也只算 `type = 1`**：`每桶 Redis 余额 == redis_qty - SUM(quantity WHERE state=0 AND type=1)`。
  `type = 2` 从不碰 Redis（确认不递减桶），把它算进去会稳定报差。
- 报告字段（`InventoryAuditVO`）：

  | 字段 | 含义 |
  | --- | --- |
  | `pendingDeductQty` | `SUM(quantity) WHERE state=0 AND type=1`（虚拟 = 全部 state=0）：合并停跑只增、恢复归 0 |
  | `pendingClaimQty` | 实物 `SUM(quantity) WHERE state=0 AND type=2`：确认没落地就长期 > 0，**新增告警读数** |
  | `mergedDeductQty` | `SUM(quantity) WHERE state=1 AND type=1` |
  | `ledgerWaitingQty` / `ledgerIssuedQty` | 台账两列（虚拟 `waiting` 恒 0） |
  | `ledgerConsistent` | 上面那条等式 |
  | `redisConsistent` | 三态：`true` / `false` / `null`（Redis 不可读不下结论） |

- 对账**不自动修数据**（票 12 的既有口径）：差值只暴露，人工核。

## 9 预热、故障与行为边界

- **预热**（`inventoryBucketWarmup`，调度仍不启用）：`SET` 语义（不是 INCR），参数改成
  `[quota|virtual:]invId`，空 = 两段全量。在途未合并时严禁重灌（会把已扣量灌回去）——既有告警保留。
- **Redis 故障语义不变**：连不上 → `BucketUnavailableException`（故障，抽奖失败）；桶内余额不足 / 未预热 →
  `deducted = false`（业务事实）。可用性优先级：宁可抽奖失败，也不假装发放。
- **DB 是库存真相，Redis 只是桶余量面**：合并、对账、目录展示（`selectPrizeCatalog` 的 `remaining/total`）
  全部读台账；不要改成读 Redis。
- **漂移的三种结局不变**（ADR-0005 §Redis 的固有问题）：少卖可容忍，超卖必须人工核，绝不自动修。
- **消费者是 at-least-once**：合并必须幂等（已由 §5 保证），不要假设一条消息只来一次。

## 10 受影响的测试

测试栈**没有 broker**（`application-test.yml` 只有 namesrv）：发送必然失败、被 outbox 兜住。
所以「消息发出」的可观察形态 = **outbox 落行 + 载荷正确 + `delay_level = 1`**；
「消费」由测试**直接调消费者 Bean**（照 `lotteryShippingConsumer.onMessage(...)` 的先例）。

| 文件 | 改动 |
| --- | --- |
| `support/RaffleApiTestBase.java` | 删 `insertPhysicalInventory` 夹具（表没了）；`TRUNCATE` 列表换成新表名；`insertVirtualInventory` / `insertQuota` / `insertQuotaBucket` 换表名；`prepareInventory` 不再给配额铺「库存台账」行 |
| `inventory/VirtualInventoryIntegrationTest.java` + `inventory/PhysicalQuotaIntegrationTest.java` | **合并成一个** `InventoryQuotaIntegrationTest`：同一套预扣 / 合并 / 幂等 / 限量耗尽 / 兜底 / 对账用例按 `segment` 参数化跑两遍（两段同构，用例也该同构） |
| `inventory/InventoryFaultIntegrationTest.java` | 换表名、换服务名；驱动合并从 `mergeOnce()` 改成 `mergeKey(...)` / `backstopOnce()`；补「Redis 挂了 → 预扣抛、明细不落、对账可查」 |
| `raffle/RaffleDrawPipelineIntegrationTest.java` | 换表名；补「同 token 并发双击只净扣一份」（明细唯一键） |
| `raffle/RaffleShippingConsumerIntegrationTest.java` | 兑换确认改成**落 `type=2` 明细 + outbox 行**（不再断言 `waiting_qty` 当场转移、不再断言 4312）；转移由合并用例覆盖 |
| `raffle/RaffleShippingOutboxIntegrationTest.java` | 中奖事件不受影响；补一条「库存合并消息的 `delay_level = 1`、中奖事件仍是 0」 |
| 新增 `inventory/InventoryMergeTriggerIntegrationTest.java` | 触发与合并的硬口径：① 扣成功才发消息、窗口内第二笔不发（throttle）；② 明细 + outbox 同事务（探针回滚 → 两条都没有）；③ 只合 `create_time < NOW()` 的格；④ 只合最老一格（type=1 先于 type=2）；⑤ `affected != cnt` 时整格回滚；⑥ 10 格上限 + 还有未合就续命；⑦ 兜底 60s 的 key 选取与 `mergeKey` 直调 |
| `inventory/InventoryQuotaIntegrationTest.java` 追加对账用例 | 统一恒等式：`waiting + issued == merged(type=1)`（虚拟 `waiting` 恒 0）、Redis 恒等式只算 `type=1`、`pendingClaimQty` 在有未落地确认时 > 0；两段参数化各跑一遍 |

- **跑之前先重建 `bs_marketing_test`**（§3）；这是本 effort 唯一会让测试假绿的操作风险。
- 前端零改动：库存没有 HTTP 面（`docs/api/` 里没有库存接口）；抽奖目录的 `remaining/pointsAmount`
  口径不变，只换表名。

## 11 受影响的文档与 ADR

| 落点 | 改什么 |
| --- | --- |
| `backend/marketing/docs/db/inventory.md` | **重写**：6 张表（8 → 6）、明细表新列与新索引、两段合一的服务形状、触发（消息 + 兜底）、对账两式、Redis key、`state` 两态 |
| `backend/marketing/docs/README.md` | 库存行：`virtual_inventory_quota_* 3 张 + physical_marketing_quota_* 3 张` |
| `backend/marketing/docs/db/draw.md` | 主链路里「预扣 → 合并」的触发描述；发货状态机表里「出库台账」与 `confirmIssued` 的措辞；「兑换确认排在推进之前」那一整条（4312 已退休，顺序保留但不再承重）；「合并扫描不连接活动表」改成「合并按 key 合明细、不看活动」 |
| `backend/marketing/docs/api/lottery.md` | 定时任务表（`inventoryMerge` → `inventoryMergeBackstop`）；事件表加 `bs-inventory-merge`；「发货消费的重投口径」整节改成异步确认（4312 退休、人工核窗口消失）；错误码表删 4312；目录 `pointsAmount` 的 JOIN 换表名 |
| `backend/marketing/docs/architecture/timeout.md` | 两处 `inventoryMerge`（「不是 deadline」那张表、结尾「把 …迁进来」）改为已删除 / 由消息 + 兜底取代 |
| 根目录 `sql.md` §2 | 删两张、改三张名与新列新索引、营销 `outbox_message` 加 `delay_level`；`.scratch/project-rework/cmp_schema_marketing.py` 必须跑通 |
| `CONTEXT.md` | 改「**预扣**」（唤醒源从每秒任务改成 1 秒延迟消息 + 60 秒兜底；明细带业务唯一键；预扣不碰台账这句不变）、改「**奖品类型**」（实物档的兑换确认变成「落 type=2 明细、由合并转移」，不再同步抛 4312）、改「**超时任务**」的 `_Avoid_` 里「延迟消息」那句（补边界：超时任务不用 MQ 延迟等级；库存合并的 1 秒唤醒用它，两者不是一回事）；新增「**库存配额**」「**时间格合并**」两条词条（统一表达两段同构的模型） |
| `docs/adr/0005-inventory-merge-aggregate-scan.md` | **superseded**：顶部加 front matter `status: superseded by ADR-0011` + 一行指针（它否决的第 2 条「MQ 延迟消息」被反转、第 4 条 RDQ 升级路径不再走）；原文保留作为决策史 |
| `docs/adr/0011-inventory-merge-delay-message.md` | **新建**：本次决策（outbox + MQ 延迟级别 1、时间格 + affected 判重、60s 兜底、类型分叉）+ 被否选项（每秒全表扫、RDQ、DB 唯一键做消息去重）+ Consequences（重复投递、1s 量化、兜底是必需件） |
| `.scratch/timeout-inline/map.md` | `inventoryMerge` 那条判据的末尾追加一行：该 job 已被本 effort 删除，改为「1 秒延迟消息 + 60 秒兜底」，判据随之失效（不是超时任务） |

## 12 已知波及与非目标

- **`OutboxMessageSender` 是 points / raffle / inventory 共用件**：改它（加 `delay_level` 参数）必须保证
  points / raffle 的既有消息 `delay_level = 0` 行为完全不变（`RaffleShippingOutboxIntegrationTest` 的
  「回滚后没有第二条事件」「重试耗尽进死信」两条断言要照旧绿）。
- **`inventoryMerge` 从 xxl-admin 摘掉**：`xxl-job-tasks.sql` 删掉该 INSERT 块，并补一条幂等清理
  （`DELETE FROM xxl_job_info WHERE executor_handler = 'inventoryMerge' AND job_group = @group_id;`
  或 `trigger_status = 0`），否则调度仍会派给一个已删的 handler、日志刷「找不到执行器任务」。
  新任务 `inventoryMergeBackstop` 用 `trigger_status = 1` 直接启用。
- **`docs/api/` 没有库存接口**：库存域无 HTTP 面；「若有库存接口一并改」这一条落空，只改 `lottery.md` 里
  与库存相邻的那几节（定时任务 / 事件 / 错误码 / 目录口径）。
- **抽奖侧接口签名变更**：`IInventoryDeductPort` 的两个方法加 `recordId`（破坏性）；`IQuotaConfirmPort` 的
  `redeemOrderId` 正名为 `recordId`（它一直是抽奖记录 id，不是兑换单号）；`RaffleDrawServiceImpl` /
  `RaffleShippingCase` / 两个端口实现同步改；抽奖域不引入库存域的 VO。
- **`4312` 退休**：`ResponseCodeEnum.INVENTORY_WAITING_SHORTAGE` 删除；它不是前端契约（只在发货消费内部抛）。
- **key 前缀变更**：dev 环境的旧 key 变成孤儿（无害）；上线/联调前重跑 `inventoryBucketWarmup`。
- **非目标**（同 §1）：分布式锁 / 认领态 / `FOR UPDATE` / DB 唯一键做消息去重 / 出入库流水表 /
  多桶分摊 / 关桶 / Redis 重建看门狗 / 前端改动（零改动）。

## 13 落地票索引

拆票口径：**按「能独立验收的一步」切，且每张票的落地预算 ≤ 200k token（一个新鲜上下文）**；
不在票里重开设计讨论。依赖是一条主链 + 两个支票（04 只被 01 门禁，09 只被 04 / 05 门禁），
推荐顺序 `01…12`（上一票绿了才动下一票）。
逐票正文（含预算、止损线、验收项）见
[`marketing-inventory-refactor-impl/issues/`](../marketing-inventory-refactor-impl/issues/)，依赖图见
[impl/map.md](../marketing-inventory-refactor-impl/map.md)。

| 阶段 | 票 | 依赖 | 一句话 |
| --- | --- | --- | --- |
| A schema 收口 | 01 → 02 → 03 | — | 删进出库台账、虚拟三表改名、明细加业务键/唯一键/两条索引；每次改表都重建两个库 |
| B 共享件 | 04 | 01 | outbox 加 `delay_level`：扫描重发也按列走，points / raffle 行为不变 |
| C 孪生合一 | 05 → 06 → 07 | 03 | 一条 `segment` 路径 + 预热/对账收口 + 两个集成测试合并参数化 |
| D 触发与合并 | 08 → 09 → 10 | 05 · 04 | 时间格合并引擎（仍每秒驱动）→ 预扣发消息 → 消费 + 60s 兜底 + 续命 |
| E 确认与收尾 | 11 → 12 | 10 | 兑换确认异步化（`type=2`、4312 退休）→ 文档收口 + 全量回归 |

验收基线：落地后 `backend/marketing/` 全量集成测试绿，且「重建 `bs_marketing_test` 再跑」仍绿
（不重建就跑出来的绿不算）。
