# 奖品库存两层模型重构计划（执行 handoff）—— **已废弃**

> **状态：已废弃（2026-11，award 域重构）**：本文记录的是「配额池保留 + 分配链路」的两层模型计划；
> 后续重构把它进一步收窄为**单一奖品库存台账**——池表（`virtual_inventory_quota*` /
> `physical_marketing_quota*`）、分配流水 `inventory_allocation_flow`、分配 case/controller 全部删除，
> 运营改为直接 `POST /api/marketing/award/inventory` upsert `award_inventory`，
> 对账轮移除 B1。**现行口径以 [db/award.md](db/award.md) 与 `better-school/sql/schema.sql` 为准**；
> 原文保留作决策史，不要再按本文落地。
>
> 原目标：抽奖不再直接扣"外界给的配额池"（`virtual_inventory_quota` / `physical_marketing_quota`），
> 而是把配额**分配**给奖品位，抽奖只扣 `award_inventory`（统一台账，`type` 区分实物/虚拟）。
> 本文档是唯一执行依据；按顺序做，每节末尾有自检。
>
> 已拍板的三个决策：
> 1. **分配即转移**：分配时池 `surplus_qty -= N`、奖品库存 `surplus_qty += N`，一个事务；
> 2. **保留两段式**：统一表保留 `surplus/waiting/issued` 三列，实物行走两段式，虚拟行 `waiting` 恒 0（预占直接 issued）；
> 3. **改到全链路**：DDL + 文档 + Java 全部落地；**改完不起环境、不跑测试**，只做编译级验证。

---

## 0. 目标模型（先对齐再看 SQL）

```
外界投放（运营）                     抽奖链路
      │                                │
      ▼ 分配（即转移，一事务）           ▼ 预扣/确认（合并器，只打奖品库存）
┌─────────────────────────┐   ┌──────────────────────────────────┐
│ 配额池（保留，不再被抽奖扣）│   │ award_inventory     │
│ virtual_inventory_quota  │──▶│   type = VIRTUAL：surplus→issued  │
│ physical_marketing_quota │   │   type = PHYSICAL：两段式          │
│ + 各自 detail 表          │   │   surplus / waiting / issued      │
└─────────────────────────┘   │ award_inventory_    │
                              │   detail（统一明细，flow_type 区分）│
                              └──────────────────────────────────┘
```

关键语义变化：

| 旧 | 新 |
|---|---|
| `InventorySegmentVO`（VIRTUAL/QUOTA）选两张台账表 | `AwardInventoryTypeEnum`（VIRTUAL/PHYSICAL）是统一台账里的一列 |
| 抽奖 `invId` = 池台账行主键 | 抽奖 `invId` = 奖品库存账户（按 `award_id` 1:1） |
| 池台账被预扣直接 UPDATE | 池台账只被"分配"转移；预扣/确认全部打奖品库存表 |
| 实物/虚拟 = 两套 DAO/PO/明细表 | 一套 DAO/PO/明细表，`type`（台账）/ `flow_type`（明细）分支 |

**守恒口径（新）**：

- 奖品库存（实物行）：`waiting_qty + issued_qty == SUM(明细 flow_type=1)`；`type=2` 行在两列间搬，和不变；
- 奖品库存（虚拟行）：`issued_qty == SUM(明细 flow_type=1)`；
- 分配行（`flow_type=0`）不进上面两个等式（它只在 `surplus` 与池之间搬运）；
- 池侧：`surplus_qty` 只被分配扣减，对账靠分配流水（见 §2 的开放点 ③）。

---

## 1. DDL（`sql/schema.sql`）

### 1.1 新增 `award_inventory`

```sql
CREATE TABLE IF NOT EXISTS award_inventory (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    award_id    VARCHAR(36)  NOT NULL COMMENT '奖品位 id（draw_strategy_award.id），1 奖品位 1 库存账户',
    type        VARCHAR(16)  NOT NULL COMMENT 'PHYSICAL 实物（两段式）/ VIRTUAL 虚拟（waiting 恒 0）',
    surplus_qty BIGINT       NOT NULL DEFAULT 0 COMMENT '可扣余量；-1 = 不限量（虚拟档沿用）',
    waiting_qty BIGINT       NOT NULL DEFAULT 0 COMMENT '已预占未确认（仅 PHYSICAL 使用，VIRTUAL 恒 0）',
    issued_qty  BIGINT       NOT NULL DEFAULT 0 COMMENT '已发放',
    value_qty   BIGINT       NULL COMMENT '单次发放面额（仅 VIRTUAL：POINTS 积分 / DRAW_COUNT 次数；实物 NULL）',
    state       TINYINT      NOT NULL DEFAULT 0 COMMENT '0 open / 1 exhausted / 2 closed（沿用原台账语义）',
    create_time DATETIME     NOT NULL COMMENT '由应用写入',
    update_time DATETIME     NOT NULL COMMENT '由应用写入',
    PRIMARY KEY (id),
    UNIQUE KEY uk_award_id (award_id),
    KEY idx_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='奖品库存统一台账（抽奖直接扣减的账户，由配额池分配而来）';
```

要点：

- `uk_award_id`：1 奖品位 1 账户，抽奖按 `award_id` 定位账户，不再需要池 `inv_id`；
- `type` 落**列**而不是分表：两张 DAO 合一套，`switch` 在 SQL/Java 层按 type 分支；
- `value_qty` 从虚拟池台账的 `value` 列迁移而来（虚拟奖品"每次抽发多少积分/次数"是奖品属性，跟着奖品库存走）；
- `state` 沿用原台账 state 语义（open/exhausted/closed），域服务"非 open 走重放预检"的口径不变。

### 1.2 新增 `award_inventory_detail`

```sql
CREATE TABLE IF NOT EXISTS award_inventory_detail (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    award_id    VARCHAR(36)  NOT NULL COMMENT '奖品位 id（= 台账行），幂等键前缀',
    flow_type   TINYINT      NOT NULL COMMENT '0 分配转入 / 1 预扣（PHYSICAL 进 waiting，VIRTUAL 直接发放）/ 2 兑换确认（仅 PHYSICAL）',
    quantity    BIGINT       NOT NULL COMMENT '本行份数',
    biz_type    VARCHAR(30)  NOT NULL COMMENT '业务身份：LOTTERY_DRAW / LOTTERY_CLAIM / AWARD_ALLOCATE',
    biz_id      VARCHAR(36)  NOT NULL COMMENT '幂等锚点：抽奖记录 id / 抽奖记录 id / 分配单号',
    create_time DATETIME     NOT NULL COMMENT '由应用写入',
    update_time DATETIME     NOT NULL COMMENT '由应用写入',
    PRIMARY KEY (id),
    UNIQUE KEY uk_award_biz (award_id, biz_type, biz_id),
    KEY idx_award (award_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='奖品库存统一流转明细（预扣/确认/分配转入；INSERT IGNORE + 唯一键裁决幂等）';
```

要点：

- 幂等锚点从 `(inv_id, biz_type, biz_id)` 变为 `(award_id, biz_type, biz_id)`——`award_id` 就是新的"账户维度"；
- 分配转入也走这张表（`biz_type = 'AWARD_ALLOCATE'`，`biz_id` = 分配单号），分配的幂等靠同一个 `uk_award_biz`；
- `flow_type` 与 `biz_type` 存在派生关系（LOTTERY_DRAW⇒1 / LOTTERY_CLAIM⇒2 / AWARD_ALLOCATE⇒0），保留两列是为了对账 SQL 直接按流转类型过滤，不做强一致约束。

### 1.3 池表调整（不删、改注释与守卫口径）

- `virtual_inventory_quota` / `physical_marketing_quota` 及各自 detail 表**保留**，表注释改为：
  「外界投放配额池：只被『分配』操作转移（surplus 转出），抽奖不再直接扣减」；
- 池明细表的守恒式不变（分配转出**不写**池明细——它不产生 waiting/issued 流转，见 §0 守恒口径）；
- 池台账的预扣类 SQL（`deductLedger` 等被抽奖链路使用的方法）从调用面退役，见 §4。

### 1.4 旧表去留结论

| 表 | 去留 |
|---|---|
| `virtual_inventory_quota`(+detail) | 保留，角色改为"虚拟投放池" |
| `physical_marketing_quota`(+detail) | 保留，角色改为"实物投放池" |
| `draw_record.inv_id` | **语义变更**：记奖品库存账户维度（见开放点 ①） |

---

## 2. 关键 SQL 形状（flush 服务重写的依据）

```sql
-- 预扣（PHYSICAL 行；合并器一组一事务的第一条）
UPDATE award_inventory
   SET surplus_qty = surplus_qty - N, waiting_qty = waiting_qty + N
 WHERE award_id = ? AND type = 'PHYSICAL'
   AND (surplus_qty < 0 OR surplus_qty >= N);

-- 预扣（VIRTUAL 行；直接发放，无 waiting）
UPDATE award_inventory
   SET surplus_qty = surplus_qty - N, issued_qty = issued_qty + N
 WHERE award_id = ? AND type = 'VIRTUAL'
   AND (surplus_qty < 0 OR surplus_qty >= N);

-- 兑换确认（仅 PHYSICAL；waiting → issued，c = 本次新增明细行数）
UPDATE award_inventory
   SET waiting_qty = waiting_qty - c, issued_qty = issued_qty + c
 WHERE award_id = ? AND type = 'PHYSICAL' AND waiting_qty >= c;

-- 分配（池 → 奖品，一个事务两条 UPDATE + 两边流水/幂等）
UPDATE physical_marketing_quota      -- 或 virtual_inventory_quota，按池段
   SET surplus_qty = surplus_qty - N
 WHERE inv_id = ? AND surplus_qty >= N;
UPDATE award_inventory
   SET surplus_qty = surplus_qty + N
 WHERE award_id = ?;
```

- 预扣"扣不动不二分（探一次再切）"、撞键剔除、快照过期重试**全部原样保留**，只是目标表换成统一台账；
- 分配**不走合并器**（低频运营操作），域服务用 `TransactionTemplate` 同步一事务；
- 确认 `waiting` 不足仍是不变量破坏（抛 `IllegalStateException`，MQ 重投→死信），语义不变。

### 开放点（执行时先向用户确认，不阻塞前面各节）

> **执行结果（2026-11-15 全部落地，四个开放点均已拍板并实现，见下 ✅）**
>
> 1. `draw_record.inv_id` 记**奖品库存账户的 award_id**（不加快照列，来源池从分配流水反查）；
> 2. 虚拟奖品类型**不冗余列**——台账只留 `value_qty` 面额，POINTS/DRAW_COUNT 的区分留在池配置
>    （读侧面额拼装走 award_id → inv_id → 池 type=POINTS 两步）；
> 3. **池侧建独立分配流水表** `inventory_allocation_flow`（`uk_alloc_biz` 分配单号幂等）：
>    分配事务 = 奖品侧 `AWARD_ALLOCATE` 明细 + 池侧分配流水 + 池守卫转出 + 台账 upsert 转入，
>    双侧流水同锚分配单号，撞单号按成功回放；
> 4. 存量数据**清库重建**（未上线），无迁移 SQL。
>
> 计划外增量（两层模型的语义牵连，已一并落地）：
> - 读侧适配：奖品目录 `remaining/total` 改读奖品库存账户（`IAwardInventoryDao.selectByAwardIds`），
>   `pointsAmount` 仍读池面额配置；`listDrawRecords` / `queryPointsValueByAwardId` 按
>   `draw_record.inv_id = awardId` 走 award → inv_id → 池三段查；
> - 新增分配入口：case 层 `QuotaAllocateCase` + trigger `POST /api/marketing/inventory/allocate`（ADMIN）；
> - 池侧退役：`IPhysicalQuotaDao` / `IVirtualInventoryQuotaDao` 的 `deductLedger` / `selectSurplus` /
>   `transferWaitingToIssued` / `selectPhysicalQuotasByInvIds` 删除，新增 `deductForAllocate`（分配守卫转出）；
>   两张池明细 DAO / PO / mapper XML 整体删除（无调用方）；
> - 引擎顺带修复：`QuotaMergeBuffer.flushOneGroup` 原 `map(...).toList()` 的泛型推断落不到
>   `List<DeductPending>`（源码历史遗留不可编译），改为先收集 pending 件再传私有组方法；
> - 编译验证：环境无 Maven，用 JDK 21 javac + 本地 .m2 仓库按依赖序编译——主源 7 模块 + 测试源
>   （types / domain / app）全部通过（仅两条既有 deprecation 提示，无错误）。集成测试按约定**未运行**。

1. `draw_record.inv_id` 记什么：✅ 记**奖品库存账户的 award_id**；
2. 虚拟池的 `type`（POINTS/DRAW_COUNT）：✅ 不冗余，只留 `value_qty`；
3. 池侧独立分配流水表：✅ 已建 `inventory_allocation_flow`；
4. 存量数据迁移：✅ 清库重建。

---

## 3. Java：新增/修改文件清单

### 3.1 marketing-types

- **新增** `AwardInventoryTypeEnum`（`PHYSICAL` / `VIRTUAL`）：奖品库存层的类型枚举；
- `InventorySegmentVO` **保留**，但语义收窄为"配额池段"（只被池查询/分配/运营侧使用）。

### 3.2 marketing-domain

| 文件 | 改动 |
|---|---|
| `IAwardRepository` | `flushDeductGroup(segment, invId, requests)` → `flushDeductGroup(AwardInventoryTypeEnum, awardId, requests)`；`flushClaimGroup(invId, items)` 参数语义变为 awardId（签名不变）；`existsDeductDetail(segment, …)` → 按 `(awardId, bizType, bizId)` 查统一明细（segment 参数删除）；**新增** `allocate(segment, poolInvId, awardId, qty, allocBizId)`（分配，同步一事务）；池台账查询方法（`queryLedger`/`listOpenLedgers`）语义收窄为"池台账"，奖品库存台账新增查询方法 |
| `IAwardInventoryService` / `AwardInventoryServiceImpl` | `preDeduct`/`confirmIssued` 的 `invId` 参数语义变为奖品库存账户（awardId）；javadoc 全部按两层模型重写；`alreadyDeducted` 的重放预检走新明细 |
| 合并器三个 Pending 类 | `segment()` 访问者 → `type()`（返回 `AwardInventoryTypeEnum`）；`DeductPending.segment()` 从 `request.segment()` 改为请求携带的 `type`；`ClaimPending` 仍恒 null（确认仅实物）。**分组键三维不变**：动作 × type × awardId |
| `QuotaMergeBuffer` / `ShardBuffer` / `MergeConfig` | 分组键第二维换枚举类型，其余（单飞、攒批、双缓冲、停机）**零改动** |
| 新增 `IInventoryAllocationService`（或并入 quota service） | 分配的域服务：校验池 open + 奖品位存在 → `repository.allocate(...)`；同步事务，不进合并器 |

### 3.3 marketing-infrastructure

| 文件 | 改动 |
|---|---|
| **新增** `IAwardInventoryDao` / `IAwardInventoryDetailDao` + PO + mapper XML | 统一台账/明细的 CRUD：`selectByAwardId`、`deductPhysical(...)`、`deductVirtual(...)`、`transferWaitingToIssued(...)`、`allocateIn(...)`、明细 `batchInsertIgnoreDeductDetails` / `insertIgnoreClaimDetail` / `insertIgnoreAllocateDetail` / `listExisting...`（全部带 `award_id` 前缀的 uk） |
| `AwardInventoryFlushService` | 重写：两组 flush 方法的目标表换统一台账；`flushDeductGroup` 内按 type 分支两条 UPDATE（§2 的形状）；撞键快照/探一次再切逻辑原样搬移，只是查明细改为新表 |
| `AwardRepository` | 双段 switch 大部分退役；保留池台账查询（运营/分配用）；flush/exists 委托改新 DAO；新增 `allocate` 实现（一个事务：池 surplus 守卫扣减 + 奖品库存 surplus 增加 + 奖品明细 AWARD_ALLOCATE 行 `INSERT IGNORE`，撞分配单号=幂等回放） |
| 池侧 DAO（`IVirtualInventoryQuotaDao` / `IPhysicalQuotaDao`） | 保留台账查询与**分配扣减**；`deductLedger` 等预扣入口若已无调用方则删除 |

### 3.4 marketing-case / marketing-app

| 文件 | 改动 |
|---|---|
| `DrawCase` / `DrawShippingServiceImpl` / `QuotaConfirmPort` | `confirmIssued(invId, …)` 的 invId 语义换为 awardId（签名大概率不变，javadoc + 取值来源改） |
| 抽奖主链路（票 13 usecase） | `preDeduct` 的 invId 来源从"奖品配置的池 invId"改为"奖品位对应的奖品库存账户"；`draw_record.inv_id` 落库值按开放点 ① |
| 分配入口（新增） | 管理/投放侧 usecase：调 `IInventoryAllocationService.allocate`；票号待定 |
| `QuotaMergeConfig` / `QuotaMergeBufferTest` 等引擎测试 | **零改动**（分组键维度不变，仅类型换枚举）；`RecordingQuotaFlushHandler` 的 `Group.segment` 字段改名 `type` |
| 集成测试（`InventoryQuotaConservationIntegrationTest` 等） | 全部重写：建两池+奖品库存初始数据 → 分配 → 预扣 → 确认，守恒断言按 §0 新口径 |

### 3.5 文档

| 文件 | 改动 |
|---|---|
| `docs/db/inventory.md` | 重写模型图（两层）、守恒口径、表清单补两张新表、删除"旧表已删"段落里过时内容 |
| `docs/db/draw.md` | `draw_strategy_award` 行补一句"库存账户 1:1 见 award_inventory"；`draw_record.inv_id` 注释按开放点 ① |
| `docs/architecture/*` | 检索 `SegmentVO` / 池台账 的描述，同步两层模型说法 |

---

## 4. 执行顺序（建议 6 步，每步可独立编译）

1. **DDL + PO + DAO + mapper**：新表两个 PO/DAO/XML 落地，`schema.sql` 更新（含池表注释）——纯新增，可编译；
2. **types + domain 端口**：`AwardInventoryTypeEnum`、`IAwardRepository` 签名调整（此步会让 infrastructure 编译红，紧接第 3 步）；
3. **infrastructure 重写**：`AwardInventoryFlushService` / `AwardRepository` / `allocate` 实现；
4. **domain 引擎与服务**：Pending 三类访问者改名、`AwardInventoryServiceImpl` 语义、分配域服务；
5. **case + app**：抽奖链路 invId 来源、发货确认链路、分配 usecase；
6. **测试与文档**：引擎单测改名收尾、集成测试重写、docs 更新。

**验证口径（按约定不起环境）**：每步结束 `mvn -q compile`（或等效）保证编译通过即可；集成测试重写后**不运行**，留给后续窗口。

## 5. 风险与回退

- 引擎（合并器）本体零逻辑改动，风险集中在 flush SQL 重写与守卫条件迁移——§2 的 SQL 形状必须逐字对齐；
- `uk_award_biz` 是幂等生命线：迁移期若新旧明细并存，同 bizId 会在两张表各落一行（互不撞键）——上线前必须清空或迁移完毕，不允许双写；
- 回退 = 还原 schema.sql 与本次改动文件（git）；模型无跨服务外溢（调用方只有本服务）。
