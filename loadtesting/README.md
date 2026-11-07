# 库存扣减压测工程

把线上营销域「库存配额」的三种扣减方式拆成三个可独立施压的接口，用同一套表、同一套接口、
同一套 JMeter 脚本对比，回答一个问题：**单热点库存下，把扣减从「DB 行锁」变成「Redis 预扣 + 合并落账」，到底能换来多少吞吐、付出多少一致性延迟。**

三台机器的部署与操作见 **[OPERATIONS.md](OPERATIONS.md)**（复制粘贴即可），实测结果与分析见 **[REPORT.md](REPORT.md)**。

---

## 1. 三种策略

| strategy | 请求线程做什么 | 消费线程做什么 | 台账写入次数 | 响应语义 |
|---|---|---|---|---|
| `db` | `UPDATE inventory_ledger SET surplus_qty = surplus_qty - 1 WHERE sku_id = 1001 AND surplus_qty >= 1` + 插订单，同一事务 | 无 | **每单 1 次** | 同步强一致 |
| `redis_mq` | Redis Lua 原子扣减 → 发一条 MQ → 返回 | 每条消息：插订单 + `UPDATE` 台账 | **每单 1 次** | 受理即返回，DB 异步最终一致 |
| `merge` | Redis Lua 原子扣减 → 落一条未合并明细（唯一键防重）→ 窗口内首单发一条 1s 延迟消息 | 每秒每桶一次：把最老的一个「秒格」批量翻账（`UPDATE` 明细 state + 台账 + 桶副本） | **每秒每桶 1 次**（一次更新几百到几千行） | 受理即返回，台账异步合并 |

三种策略共用同一张台账表 `inventory_ledger`，所以「写入放大」是可直接对比的：

- `db`：1500 次请求 = 1500 次台账 `UPDATE`
- `redis_mq`：1500 条消息 = 1500 次台账 `UPDATE`（只是换了线程池排队）
- `merge`：1500 次请求 = 1~2 次台账 `UPDATE`（实测 500 单合成 1 次，20000 单合成 16 次）

### 为什么 merge 快

请求路径上只剩「一次 Redis Lua + 一次明细 INSERT」，台账那一行不再被每个请求排队抢锁；
合并线程每秒只碰台账一次。代价是台账**短暂滞后**（在途量记在 `waiting_qty`），
以及 Redis 挂掉会丢「尚未合并」的那部分余量——所以线上要用台账对账、明细补账。

### 为什么 redis_mq 会被自己堵住

请求很快（不含 DB），但消费端每条消息都要 `UPDATE` 同一行，行锁在消费线程之间排队。
MQ 削的是峰，不是写放大：吞吐上限仍然由那一行决定，只是把延迟从请求侧挪到了积压里。
所以它的 `stats.invariant.drained` 会长时间为 `false`，`orders` 追不上 `ok:redis_mq`——
这不是 bug，正是这条路线要暴露的成本。

---

## 2. 目录结构

```
loadtesting/
├── README.md                       # 本文件：设计说明、接口、指标口径
├── OPERATIONS.md                   # 三台机从零到出数（复制粘贴）
├── REPORT.md                       # 压测结果与分析（4C8G 三机实测）
├── docker-compose-middleware.yml   # 中间件机：MySQL + Redis + RocketMQ(namesrv/broker)
├── docker-compose-app.yml          # 应用机：库存压测服务
├── lt-inventory-app-1.0.0.tar      # 应用镜像，docker load -i 导入
├── app/                            # 源码（重建镜像才需要）；schema.sql 应用启动时自建表
│   ├── Dockerfile.runtime          # 把 target/*.jar 装进 JRE 镜像（生成 tar 包用）
│   ├── Dockerfile                  # 多阶段构建（本机能联网时可直接 docker build）
│   ├── pom.xml
│   └── src/main/java/.../inventory
│       ├── api/                    # 扣减接口 + admin(reset/stats)
│       ├── strategy/               # db / redis_mq / merge 三个实现
│       ├── merge/                  # 时间格合并引擎 + 桶锁 + 兜底扫描
│       ├── mq/                     # RocketMQ 生产/消费
│       ├── repository/             # 全部 SQL 在这里
│       └── metrics/                # 应用侧计数、runId、桶布局
├── jmeter/inventory-deduct.jmx     # 三种策略共用的压测脚本
└── scripts/
    ├── build-image.sh              # 开发机：编译 -> 打镜像 -> 导出 tar 包
    ├── oci2docker.py               # 把 OCI 归档转成老版本 Docker 能 load 的经典归档
    ├── run-test.sh                 # 一条命令 = 重置 + 施压 + 等排空 + 打账
    ├── reset.sh                    # 只重置
    └── stats.sh                    # 只看账
```

中间件机只需要一个 `docker-compose-middleware.yml`（自包含，不挂宿主机文件）；
应用机再加一个 `lt-inventory-app-1.0.0.tar`。
两份 compose 都是自包含的（参数全写死、不挂宿主机文件），**源码不用上服务器**。

---

## 3. 接口

### 扣减（压测入口）

```http
POST /api/inventory/deduct
Content-Type: application/json

{"strategy":"merge","skuId":1001,"qty":1,"orderNo":"a-uuid"}
```

```json
{"accepted":true,"strategy":"merge","orderNo":"a-uuid"}
{"accepted":false,"reason":"SOLD_OUT","strategy":"merge","orderNo":"a-uuid"}
```

- 卖完也返回 **HTTP 200**（`accepted=false`），这样 JMeter 的「错误率」只统计真正的技术故障。
  因此**受理数不要看 JMeter 的成功率，看 `stats.counters["ok:<strategy>"]`**。
- `orderNo` 必须唯一（订单表 / 明细表都有唯一键）。JMeter 用 `${__UUID()}` 生成。
  重复提交同一单号 = 幂等回放：`merge` 会把这次 Redis 扣减还回去，只算一单。
- `strategy` 可省略，取 `DEFAULT_STRATEGY`（默认 `merge`）。
- 也可写成 `POST /api/inventory/{strategy}/deduct`，方便 curl 手工验。

### 重置一轮

```http
POST /api/admin/reset?skuId=1001&stock=200000&buckets=1
```

清订单/明细、台账置回 `stock`、重建分桶、桶余量灌回 Redis、换新一轮 `runId`、计数清零。
`runId` 的作用：上一轮还在队列里的 MQ 消息（带旧 runId）会被消费者直接丢弃，
不会打到新一轮的台账上。**reset 之后等 3 秒再施压**（让在途消息过期）。

### 读一轮的账

```http
GET /api/admin/stats?skuId=1001
```

```json
{
  "ledger":  {"total":200000,"surplus":95875,"waiting":104125,"issued":0},
  "redis":   {"total":95875,"buckets":[{"bucketId":0,"redisQty":95875,"ledgerCopy":95875}]},
  "merge":   {"pendingRows":0,"pendingQty":0,"mergedRows":104125},
  "orders":  0,
  "counters":{"ok:merge":104125,"merge_runs":92,"merge_rows":104125,...},
  "serverSide":{"merge":{"calls":104125,"avgServerMs":2.19}},
  "invariant":{"sold":104125,"balanced":true,"oversold":false,
               "redisInUse":true,"redisMatchesLedger":true,"drained":true}
}
```

---

## 4. 表与不变量

```sql
inventory_ledger        -- 台账（真相）：total_qty / surplus_qty / waiting_qty / issued_qty
inventory_bucket        -- 分桶余量副本（reset 写入，合并时递减）
inventory_order         -- 订单流水（db / redis_mq 每单一行）
inventory_deduct_detail -- 预扣明细（merge）：state=0 未合并，create_time 秒精度 = 时间格
```

不变量（`stats.invariant` 已算好）：

```
surplus_qty + waiting_qty + issued_qty == total_qty     → balanced
sold = waiting_qty + issued_qty <= total_qty            → !oversold
排空后：Redis 桶余量合计 == surplus_qty                  → redisMatchesLedger
且 pendingRows == 0                                     → drained
```

任何一条不满足，就说明这一轮数据有问题（超卖 / 丢单 / 合并没收敛），先别采信 QPS。

> `db` 策略不碰 Redis，所以 `redisInUse=false` 时 `redisMatchesLedger` 恒为 `true`，属正常。

---

## 5. 合并扣减的关键实现（与线上同构）

```
请求：Lua「GET 余量 → 不足返回 -2 → DECRBY → SETNX 合并去重 key(PX 1000)」
      → 命中则 INSERT 明细（uk_biz 防重）
      → notify=1 时发一条 delayLevel=1 的 MQ（1 秒后唤醒）

唤醒：取该桶 state=0 的最小 create_time（最老一格）
      → 统计这一格的行数与数量和，并记下 MAX(id) 作为快照边界
      → UPDATE ... WHERE state=0 AND create_time=? AND id<=maxId
         affected != 统计行数 → 说明被别的实例抢走，整格回滚
      → UPDATE 台账（surplus -= qty，waiting += qty）+ 递减桶副本，同一事务
      → 还有积压就再发一条 1s 延迟消息自我续命
```

和线上 `marketing` 域一致的几点：

1. **台账是真相，Redis 只是余量面**：合并、对账、展示都读台账。
2. **一格一个事务，只合最老一格**（`MERGE_CELLS_PER_WAKE=1`）：这是后续「确认」状态
   （`waiting → issued`）守卫恒成立的结构前提。
3. **业务唯一键防重**：撞键 = 这一单已落过，回补 Redis 后按成功回放。
4. **兜底扫描**：唤醒消息丢了 / 流量突然停了，`MERGE_FALLBACK_MS`（默认 5s）扫陈旧格补发。
5. **明细 `create_time` 用 DB 时间**（秒精度），不依赖应用机时钟。

压测版和线上唯一的差异：**合并完还有积压时会自己再发一条延迟消息**（自我续命）。
线上靠持续流量自然唤醒；压测流量会停，不续命的话尾部要等 60s 兜底，收敛时间就没法测。

---

## 6. 指标口径

| 想回答的问题 | 看哪里 |
|---|---|
| 受理吞吐 | JMeter 的 Throughput（或 `stats.counters["ok:<strategy>"] / 实际压测秒数`） |
| 响应延迟 | JMeter 的 p95/p99（db 策略里包含等行锁的时间） |
| 服务端耗时 | `stats.serverSide.<strategy>.avgServerMs`（不含网络，适合对比三策略自身开销） |
| DB 写放大 | `stats.counters.merge_rows` / `merge_runs`（merge）；或中间件机 `Innodb_rows_updated` 前后差 |
| 一致性收敛 | 轮询 `stats.invariant.drained`，记录从压测结束到 `true` 的秒数 |
| 是否超卖 | `invariant.oversold=false` 且 `counters["ok:xxx"] == STOCK`（卖完场景） |

---

## 7. 本地验证过的参考量级

> Docker Desktop / Windows / 单机（MySQL、Redis、RocketMQ 与应用同机），**只用来确认链路正确，
> 不是服务器性能基线**。你的三台机结果会明显不同（尤其 DB）。

| 场景 | 受理 QPS | 平均 / 最大延迟 | 台账写入 | 收敛 |
|---|---|---|---|---|
| db，20 线程 15s，库存 20 万 | ~860 | 20ms / 98ms | 12999 次 | 即时 |
| redis_mq，20 线程 15s，库存 20 万 | ~6000 | 2ms / 142ms | 6s 内只落 3.4 万（消费端排队） | 明显滞后，`drained=false` |
| merge，20 线程 15s，库存 20 万 | ~6930 | 2ms / 50ms | 104125 行只触发 **92 次**台账更新 | 2s 内 `drained=true` |

一致性：三种策略在 500 库存 / 1500 并发请求下均**恰好受理 500 单、零超卖**；
`merge` 的 500 单合并成 1 次台账更新；同单号重复提交 5 次只扣 1 件（幂等回放）。

> 上表是单机（应用与中间件同机）的参考量级。换成 **4C8G 三机**后绝对值会变，但**关系不变**：
> `db` 受行锁串行限制排最低，`redis_mq` 受理快而落库慢（缺口就是 MQ 堆积），
> `merge` 受理最高且台账写入少 2~3 个数量级。以 200 线程那一档的实测为准。

---

## 8. 已知边界

- **单 SKU 单热点**是有意为之：三策略比的就是「同一行上的竞争」。多 SKU 会把行锁竞争摊薄，
  `db` 的数字会好看很多，但那是另一个问题。
- `db` 策略是「一个事务两条 SQL」，`redis_mq` 是「消费端一个事务两条 SQL」，
  `merge` 的每单成本是「一条 INSERT」——这是三种架构真实的工作量差异，不是不公平。
- `redis_mq` 的 `accepted` 只代表「Redis 扣减 + MQ 收下」，不代表落库；比对吞吐时请同时给出
  「受理 QPS」和「落库速率」，否则会得出「MQ 比 DB 快 7 倍」的错误结论——快的是受理，不是落账。
- Redis 保持官方默认持久化（RDB），数据目录挂了卷；MySQL 是压测口径（异步刷盘、关 binlog）。
  Redis 的 RDB fork 会在压测中途造成毫秒级抖动，属于真实成本，不要归因到扣减策略上。
- 只实现了扣减，不做取消/回补/过期；线上那部分属另一条链路。
