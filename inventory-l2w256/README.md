# inventory-l2w256 — 库存扣减 L2W256 生产样板

**一句话**：把 1022 压测里胜出的 `L2W256`（256 分片缓冲区 + 入口驱动定时器 + 固定大小共享执行池）
单独抽成一个可直接上线的 Spring Boot 工程，作为其他业务的样板；顺便把原实现里能优化、能简化、
能补测试的地方都改掉了。

> 单热点 500 并发 **25,563 TPS**（对比 64 队列出口驱动基线 18,088，**+39%**）；
> 售罄 +35%；多热点 +10~16%。数据来源见 `../docs/结果分析-1022b.md`。

---

## 1. 它解决什么问题

热点行扣减（同一 `sku_id` 每秒上万次 `UPDATE`）在数据库里会排成一条队列：每次「加锁-提交-解锁」
都要刷一次 redo，单行吞吐被钉死在 1k 上下。解法是**在应用层把 N 笔请求合并成 1 次 UPDATE**。

合并扣减有两个正交的问题，本工程只保留胜出的那一组答案：

| 问题 | 备选 | 本工程的选择 | 为什么 |
|---|---|---|---|
| 怎么攒批 | 队列式（消费者 `poll`）/ 定时窗口式 | **定时窗口**（入口驱动） | 攒批成本落在提交侧（一次 `add`），消费侧不用逐件 `poll`；200 件省下 3.4ms/批（17µs/件） |
| 谁来执行 flush | 每队列一条虚拟线程常驻等待 / 共享固定池 | **共享固定池** | 等 5ms 的是定时器队列（不占线程），池线程只在真正落库的 0.5ms 里被占用 |
| 批内怎么落库 | 逐条 / 一条 UPDATE 扣 N 件 | **一条 UPDATE 扣 N 件** | N 次「加锁-提交-解锁」压成 1 次 |
| 扣不动怎么办 | 整批失败 / 二分拆批 / 先探库存 | **先探库存，再二分** | 售罄时 200 件若靠二分要 399 次事务，直接越过 100ms 等待上限（实测 4,611 → 21,060） |

---

## 2. 快速开始

```bash
# 1) 建库（本地有 MySQL 9.x 时）
mysql -uroot -p < deploy/init.sql

# 2) 起应用（默认连 127.0.0.1:3306/inventory_demo，root/root123）
cd inventory-l2w256
mvn clean package -DskipTests
java -jar l2w256-app/target/l2w256-app.jar

# 3) 发一笔
curl -X POST http://localhost:8080/api/v1/stock/deduct \
     -H 'Content-Type: application/json' \
     -d '{"skuId":1001,"qty":1,"bizNo":"order-0001"}'
# → {"code":"0000","info":"调用成功","data":{"bizNo":"order-0001","success":true}}

# 4) 看结果分布与积压、查当前库存
curl http://localhost:8080/api/v1/stock/metrics
curl http://localhost:8080/api/v1/stock/1001
```

一键起（MySQL + 应用，容器内）：

```bash
cd deploy && docker compose up -d --build
```

---

## 3. 模块结构（DDD 分包，依赖严格单向）

```
l2w256-app            启动层：Application + config Bean 装配 + MyBatis mapper XML 唯一存放处
l2w256-trigger        触发层：HTTP Controller / 全局异常 / 结果计数         → case, api, domain
l2w256-case           编排层：扣减、复位（复位前排空）、概览               → domain
l2w256-domain         领域层：合并引擎 + 库存查询，只依赖 types、只注入本域仓储
l2w256-infrastructure 基础设施层：DAO / 仓储实现（事务落位在这里）
l2w256-api            对外契约：IStockService + DTO + Response（将来 dubbo 就暴露这一层）
l2w256-types          枚举 / 异常
```

domain 内部：

```
domain/stock/
├── model/entity/     StockEntity（库存聚合根：skuId 是身份、qty 是状态，`isEnough()` 是售罄判据）
├── model/valobj/     DeductRequestVO / DeductResultVO / MergedDeductResult / MergeConfigVO
├── service/
│   ├── merge/        IStockMergeService + StockMergeServiceImpl（引擎）
│   │                 ShardBuffer（单分片缓冲区，核心）+ MergeTask + MergedDeductExecutor（落库）
│   └── inventory/    IStockInventoryService（查询 / 复位，非热点路径）
└── adapter/repository/IStockRepository      ← infrastructure 实现
```

**三层职责边界**（换业务时最该守住的东西）：

- `ShardBuffer` 只管攒批与单飞，不知道 SQL 长什么样；
- `MergedDeductExecutor` 只管「一批 → 几次事务」，不知道缓冲区；
- 两者用一个 `Consumer<List<MergeTask>>` 解耦，所以攒批策略与落库策略可以各自替换。

---

## 4. 一笔请求的一生

```
 POST /deduct
    │
    ▼
 case.deduct ──► mergeService.submit
    │                 │  skuId & 255  → 分片 #233
    │                 ├─ offer(task)                   ← 锁内：入缓冲区 + 排定 5ms 定时器
    │                 │                                  （攒满 200 则取消定时器、改排立即 flush）
    │                 └─ future.get(100ms)             ← 虚拟线程 park，不占载体线程
    ▼                                   ⋮ 定时器到点
 ┌──────────────────────────── 执行池线程（20 条） ────────────────────────────┐
 │ 摘批（整块换缓冲，O(1)）→ 同 SKU 一条 UPDATE 扣 N 件 → 回填 future → 续排   │
 └─────────────────────────────────────────────────────────────────────────────┘
```

四个时间常量决定吞吐上限：

```
单批周期 ≈ 窗口(5ms) + 落库(0.5ms)      吞吐 ≈ 批大小(200) ÷ 周期 × 并发批数(20)
并发批数 = min(活跃分片, flush线程, 连接池)  ← 所以 flush 线程数必须等于池大小
```

---

## 5. 参数与硬约束

| 参数 | 默认 | 约束 / 依据 |
|---|---:|---|
| `merge.shard-count` | 256 | **必须 2 的幂**（路由 `skuId & mask`）。≈ 峰值并发热点数：分片 ≥ 热点数才能保证一批只碰一行；<br>但 256 片 ≈ 20MB 缓冲 + 5 万次定时器调度/秒（约 10% 单核），1024 片到 40% |
| `merge.window-ms` | 5 | 窗口越大批越纯、延迟越高。> `wait-timeout-ms` 时请求必然先超时后落库 |
| `merge.max-batch` | 200 | 单批上限。越大越省事务，但超时风险同步上升 |
| `merge.wait-timeout-ms` | 100 | 调用方等待上限。调到 200 可把高并发超时率砍半，代价 p99 → 200ms |
| `merge.shard-capacity` | 10000 | 单分片缓冲上限，满了入口背压（`REJECTED`）。256 × 10000 ≈ 20MB 引用 |
| `merge.flush-threads` | 20 | **必须等于 `pool.maximum-pool-size`**，启动时强校验：大于池=抢连接，小于池=连接闲置 |
| `merge.soldout-shortcut` | true | 售罄短路。关掉时 200 件要走 399 次事务 |
| `pool.maximum-pool-size` | 20 | 不是越大越快：双 1 刷盘下并发越高单事务越慢；扩容只能靠合并 |
| `pool.connection-timeout-ms` | 2000 | 必须远小于合并等待上限（两个数量级），否则池排队会静默 30 秒 |

---

## 6. 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/stock/deduct` | 扣减，body `{skuId, qty, bizNo}`，`bizNo` 是幂等键 |
| GET | `/api/v1/stock/{skuId}` | 剩余库存（只读，SKU 不存在时 `qty` 为 null） |
| POST | `/api/v1/stock/reset` | 复位库存并清流水（内部先排空合并器） |
| GET | `/api/v1/stock/metrics` | 结果分布 + 异常分桶 + 当前积压 |
| POST | `/api/v1/stock/metrics/reset` | 清零计数 |
| GET | `/api/v1/stock/health` | 存活探针 |

**HTTP 状态码恒 200**，业务结果走 `code`。这样压测工具与调用方都不会把「库存不足」当成服务故障。

**不提供流水明细 / 明细笔数查询**：`stock_detail` 只为幂等键存在（`uk_biz_no`），
它的行数随请求量线性增长，`COUNT(*)` 在压测期间既是慢查询又会干扰被测事务。
要看「处理了多少笔」用 `/metrics` 的结果分布，要看「库存对不对」用 `/{skuId}` 与 `UPDATE` 的实际扣减量。

---

## 7. 结果语义：失败 ≠ 错

| `code` | 状态 | 库存动了吗 | 调用方该做什么 |
|---|---|---|---|
| `0000` | SUCCESS | 动了 | 成功 |
| `1002` | DUPLICATE | 本次没动（此前已扣） | 按成功处理 |
| `1001` | INSUFFICIENT | 没动 | 明确告知库存不足 |
| `1003` | TIMEOUT_UNKNOWN | **大概率动了**（仍在缓冲区，稍后落库） | **不能报失败**，返回「处理中」，凭 `bizNo` 查询/重试 |
| `1004` | REJECTED | 没动（入口背压） | 退避后重试 |
| `0001` | ERROR | 没动（事务回滚） | 重试 |

把 `1003` 当失败返回，用户会重复下单——这是生产事故，不是显示问题。

---

## 8. 相对原实现做了什么（优化清单）

原工程是「四档方案对照的压测台」（L0/L1/L2/L2U/L2W/L2W256 并存）。抽成生产样板时做了这些改动：

### 8.1 结构性简化

| 改动 | 收益 |
|---|---|
| 只保留 L2W256，删掉 `DeductMode` 与 mode 路由 | 去掉一条贯穿三层的分支；HTTP 路径从 `/deduct/{mode}` 变成 `/deduct` |
| 删掉 L0 数据源 / `IStockBaseDao` + l0/l1 两套 mapper 命名空间 / 两套事务模板 | 少一套 SQL 与连接池、少一半误配可能（原工程 `application.yml` 里那行「l0/l1 必须与 datasource 平级」的坑不再存在） |
| 删掉 L2U 专属代码（`tryClaim`/`tryAbandon` 三态 CAS、`unifiedGlobalCapacity` 全局闸、`TIMEOUT_RETRYABLE`） | 判死方案的代码不再需要维护，`MergeTask` 从「类 + AtomicReference」退化成两个 final 字段的小类 |

### 8.2 性能相关的改动

| 改动 | 为什么快 |
|---|---|
| **双缓冲换批**：`batch = pending; pending = new ArrayList<>()` 取代 `subList(0,n).clear()` | 原写法清头部要前移剩余元素，O(n)；换缓冲是 O(1)。单批 200 件时省一次 200 元素搬移 |
| **`setRemoveOnCancelPolicy(true)`** | 攒满提前触发每秒 cancel 上千个定时任务；不设这个策略，它们会一直躺在延时队列里等到原定窗口才被清理 |
| **去掉 `inFlightFlushes` 原子计数** | 「单飞标志 `scheduled`」本身就等价于「有在途 flush」，空闲判定直接读它，每批省一次 `incrementAndGet/decrementAndGet`（热点上的原子操作） |
| **排空等待从「10ms 轮询 + 每片抢两次锁」改为 Condition 精确唤醒** | `awaitIdle` 不再周期性抢 256 把 `ReentrantLock`；空闲判定改成读 volatile 镜像，`pendingCount` 也从 256 次加锁变成 256 次 volatile 读 |
| **单 SKU 快路径** | 批内全是同一个 SKU 时跳过分组 Map 的分配与装箱——这正是单热点主场景 |
| **`MergeTask` 去掉三态 CAS** | 每请求少一个 `AtomicReference` 对象、少两次 volatile 读写 |

### 8.3 正确性 / 可运维性

| 改动 | 原来会怎样 |
|---|---|
| **修掉单飞漏洞**：`running` 标志 + 代际号双重保护 | 原实现用「`cancel(false)` 失败 ⟹ flush 已在执行」判断单飞，但 `FutureTask` 在 callable **执行期间** state 仍是 `NEW`，`cancel` 会返回 `true`：攒满提前触发会把「正在落库的那一批」cancel 掉再排一条新的，**同一分片真的会跑出两条并发 flush**（本工程单测 `singleFlight` 能稳定复现）。后果是热点行重新排队，合并收益打折且难以复现定位 |
| 幂等命中回 **`DUPLICATE`** 而不是 `SUCCESS` | 原实现把重放也算成功，对账时「成功笔数」虚高，无法区分「真扣」与「已扣过」 |
| 停机顺序：停收 → 排空 → 关池；排空失败的在途请求**显式回填 ERROR** | 原实现直接 `shutdownNow()`，缓冲区里的请求永远等不到结果，调用方干等 100ms 后猜 |
| `flushThreads == poolSize` **启动强校验** | 原来只写在文档和 compose 注释里，配错就是把吞吐砍掉 27% 而没人发现 |
| 配置校验从「部分」补到「全量 + 跨层」 | 分片数非 2 的幂、容量 < 批上限、等待 ≤ 窗口都会在启动瞬间失败 |
| 参数校验用 `jakarta.validation` + 全局异常处理器 + 异常分桶 | 原实现手写 if 校验、异常裸奔到 HTTP 层，1205/1213/池排队/驱动超时混成一个 ERROR |
| 删死代码：`DeductResultVO.remainingQty`（恒 null，扣减响应里也不回带剩余库存）、`TIMEOUT`、`TIMEOUT_RETRYABLE`、`MergeConfigVO` 的两个旧构造器 | 恒 null 字段会让调用方误以为能拿到库存；无用的状态会让判读口径变模糊 |
| 删掉「流水明细笔数」查询（`/overview` + `COUNT(*)`） | `stock_detail` 只为幂等键存在，`COUNT(*)` 随请求量线性变慢，压测期间还会和热点写抢资源；处理量改由 `/metrics` 的结果分布给出 |
| 补 20+ 个单元测试（引擎/缓冲区/执行器/配置） | 原工程 domain 测试只覆盖配置与部分逻辑，「不丢件 / 不超卖 / 单飞 / 幂等」没有一条被锁住 |

---

## 9. 观测与压测

```bash
# 自定义指标
curl -s localhost:8080/actuator/metrics/stock.merge.pending     # 积压；持续上涨 = 消费跟不上
curl -s localhost:8080/actuator/metrics/stock.pool.waiting      # 等连接的线程数；>0 = 池是瓶颈
# 结果分布
curl -s localhost:8080/api/v1/stock/metrics
```

判读要点：

- **失败率高 + `p95≈103ms`** = `TIMEOUT_UNKNOWN`（排队超时），不是扣减失败；
  `INSUFFICIENT` 会十几毫秒返回，`REJECTED`/`ERROR` 是瞬间返回。
- **积压持续上涨** = 消费跟不上到达 → 先看 `stock.pool.waiting`：>0 是池排队，=0 是 flush 线程不够。
- **对账**：压完停 5 秒后（等合并器排空）看 `/metrics`：`SUCCESS + DUPLICATE` 应 ≈ 成功笔数，
  再拿 `/{skuId}` 的剩余库存复核 `起始库存 − 扣减量`；三条对不上就查 `ERROR` 分桶。

---

## 10. 上线前还要解决的三件事（压测定不了的）

1. **连接池隔离**：`flush-threads = 20` 意味着合并器最多能占满整池。库存的读请求（查库存、补货）
   会抢不到连接，且 `connectionTimeout=2s` 会让它们静默排队 2 秒。
   要么拆 `stock-merge-pool` + `stock-biz-pool`，要么把 flush 线程降到 14 留几条（容量 −30%）。
2. **分片数按真实热点定**：256 是模型推导，**没有扫参**。线上只有几十个爆款时 64 更好（定时器调度开销小 4 倍）。
3. **读写分离**：只能解决「读」。展示类读可以走从库，**校验类读必须回主库**（从库延迟会读到旧库存）。

---

## 11. 拿它当模板时改哪里

| 想做的事 | 改哪里 | 不用动 |
|---|---|---|
| 换成自己的业务（订单/优惠券/额度） | `domain/xxx/` 全部 + `IStockRepository` + mapper XML | `ShardBuffer` / `StockMergeServiceImpl` / `MergedDeductExecutor`（把「扣库存」换成一次回调即可） |
| 换攒批策略（队列式 / 出口驱动） | 只换 `ShardBuffer` 的实现 | 落库、幂等、外部 API |
| 换落库策略（先探库存 / 不拆批 / 异步刷盘） | 只改 `MergedDeductExecutor` | 缓冲区与触发模型 |
| 拆微服务暴露 dubbo | `l2w256-trigger` 加 `rpc/XxxDubboService implements IStockService` | `api` 层 DTO 一行不改 |
| 接 MQ 消费 | `l2w256-trigger` 加 `listener/` | 编排与领域逻辑 |

---

## 12. 测试

```bash
mvn test          # 28 个用例，毫秒级，不依赖 DB
```

覆盖的关键不变量：

- **不丢件**：2000 个并发请求，结果数 = 请求数，扣减总量精确相等；
- **不超卖**：库存 50、请求 100 → 恰好 50 成功、剩 0；
- **单飞**：同一分片任何时刻只有一条 flush（并发度断言 = 1，这条用例是为上面那个 `cancel` 漏洞写的回归测试）；
- **幂等**：同 `bizNo` 重放 → `DUPLICATE`，库存只扣一次；
- **超时语义**：落库慢于等待 → `TIMEOUT_UNKNOWN`，且这笔最终仍然会扣；
- **售罄短路**：200 件整批不够时只探一次库存（不是 399 次事务）；
- **配置校验**：分片数非 2 的幂 / 等待 ≤ 窗口 / 容量 < 批上限 全部启动即失败。

已经跑通的验证：

| 项 | 结果 |
|---|---|
| `mvn clean install` | 7 个模块全部 BUILD SUCCESS |
| `mvn test` | 28/28 通过（domain 层） |
| 启动 + 接口冒烟 | `/health` → `0000`；缺参数 → `1005` 且带字段级提示；DB 不可用时 → `1003 处理中`（不是失败），`/metrics` 记到 `TIMEOUT_UNKNOWN` |
