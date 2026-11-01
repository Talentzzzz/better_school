# 02: 虚拟三表改名 virtual_inventory_quota*

**Spec:** `.scratch/marketing-inventory-refactor/spec.md` §2.1（表收口与命名不对称）、§7.4（类名跟表名）、§11 的 README 一行

**What to build:** 把虚拟段三张表改成 `virtual_inventory_quota` / `_bucket` / `_detail`，
PO / Entity / DAO / mapper XML / 测试引用一次跟到新名字。行为一字不改；
`physical_marketing_quota*` 保持原样——**命名不对称是有意的**，配额就是营销域的投放池。

**Blocked by:** 01

**Status:** resolved

**预算：≈110k / 200k。** 纯机械改名，风险全在「漏一处引用」而不是设计。
先全局搜 `virtual_inventory` 再动，改完用 `mvn -q compile` 找漏；测试只跑一遍全量收尾。

## 验收

- [x] `schema.sql` + 根 `sql.md` §2：三张表改名，表注释同步（含 `virtual_inventory_quota` 的 `CHECK` 名）
- [x] `VirtualInventoryPO` / `VirtualInventoryBucketPO` / `VirtualInventoryDetailPO` 与三个 Entity 改名
      `VirtualInventoryQuota*`；`IVirtualInventoryDao` → `IVirtualInventoryQuotaDao`；
      `VirtualInventoryMapper.xml` → `VirtualInventoryQuotaMapper.xml`
- [x] 仓储、孪生服务、预热、对账里的类型与变量名跟着改（不改变方法语义）
- [x] `RaffleMapper.xml` 三处表名引用：`selectPointsValueByInvId` / `selectPrizeCatalog` /
      `selectDrawRecordViews`；`remaining` / `total` 口径不变
- [x] 测试基类的 `TRUNCATE` 列表与三个库存测试、故障测试里的表名全部换成新名
- [x] 重建 `bs_marketing` / `bs_marketing_test`，全量集成测试绿
- [x] `grep -rn "virtual_inventory[^_q]" backend/marketing` 只剩历史文档（文档收在 12）

## 实现要点

（2026-10-17 落地。纯机械改名：未动触发、合并、Redis key、明细列；`4312` 仍保留到 11。）

**DDL：三张表改名，本节仍是 20 张**
- `backend/marketing/sql/schema.sql` + 根 `sql.md` §2：
  `virtual_inventory` → `virtual_inventory_quota`（CHECK 名同步为 `chk_virtual_inventory_quota`）、
  `_bucket` → `_quota_bucket`、`_detail` → `_quota_detail`；三处表级 COMMENT 改成「虚拟库存配额…」，
  列定义、索引、列注释一字未动。`sql.md` §2 末尾的合并说明同步换名。
- `.scratch/project-rework/cmp_schema_marketing.py` → `sql.md sec2 tables: 20` / `schema.sql tables: 20` /
  `0 table(s) differ, 20 identical` / `逐表逐列全等 ✔`（`$env:PYTHONIOENCODING='utf-8'` 下 exit 0）。
- 按 spec §3 重建 **`bs_marketing` 与 `bs_marketing_test`**：各 20 张表，
  `virtual_inventory%` 只剩三张 `_quota` 新表，旧名零命中。

**类 / 文件改名（git mv 保留历史）**
- PO：`VirtualInventoryPO` / `VirtualInventoryBucketPO` / `VirtualInventoryDetailPO`
  → `VirtualInventoryQuotaPO` / `VirtualInventoryQuotaBucketPO` / `VirtualInventoryQuotaDetailPO`。
- Entity：三个同名 Entity 同步加 `Quota`（`VirtualInventoryQuotaEntity` 等）。
- DAO：`IVirtualInventoryDao` → `IVirtualInventoryQuotaDao`；mapper 文件名
  `VirtualInventoryMapper.xml` → `VirtualInventoryQuotaMapper.xml`。
- **mapper 文件名不影响装配**（`mapper-locations: classpath:/mybatis/mapper/**/*.xml`），
  但 **namespace 必须跟接口 FQCN 一起改**，`resultType` / `parameterType` 的全部 PO FQCN 一并改。
- **`@MapperScan` 未动**：仍是逐包列举 `...infrastructure.inventory.dao`，类名变化由
  `FqcnAnnotationBeanNameGenerator` 按 FQCN 生成 bean，无需登记单个类。
- 旧 mapper XML 已从 `target/` 清掉（`mvn -o clean test-compile`），不存在新旧两份 namespace 并存。

**引用点（按「类型 / 变量 / 注释」三类）**
- `InventoryRepository`：字段 `virtualInventoryDao` → `virtualInventoryQuotaDao`；PO/Entity 类型与
  `toInventoryEntity` / `toBucketEntity` 的入参类型跟着改。
- `IInventoryRepository`：三个 Entity 泛型返回/入参 + 类注释里的表名。
- `VirtualInventoryServiceImpl`：局部实体类型改名；方法名 `queryVirtualInventory` /
  `insertVirtualDetail` / `settleVirtualInventory` / `listOpenVirtualInventories` **保持原样**——
  spec §7.1 的 `segment` 参数化归 05，本票不改变方法语义。
- `IVirtualInventoryService` / `VirtualInventoryServiceImpl` / `VirtualInventoryIntegrationTest`
  的服务名与测试类名**不动**：05 收服务、07 合并测试，提前改会和后续票冲突。
- `RaffleMapper.xml`：`selectPointsValueByInvId` / `selectPrizeCatalog`（含 LEFT JOIN 别名 `vi`）/
  `selectDrawRecordViews` 三处表名；`remaining` / `total` 表达式零改动。
- 所有引用旧表名的代码注释（PO/Entity/DAO/仓储/域服务/`InventoryTypeVO`/`IRaffleDao`/
  `IRaffleRepository`/`RaffleDrawServiceImpl`）同步换名，使验收 grep 只剩 `backend/marketing/docs/`。
- 测试：`RaffleApiTestBase` 的 `TRUNCATE` 列表与 `insertVirtualInventory` / `insertVirtualBucket` /
  `drawnPayloadOf` / `virtualDetailCount` 四段 SQL；`VirtualInventoryIntegrationTest`、
  `PhysicalQuotaIntegrationTest`、`InventoryFaultIntegrationTest` 的夹具与断言；
  `RaffleDrawPipelineIntegrationTest` 的 `UPDATE ..._bucket`。测试辅助方法名按 spec §10 留给 07。

**验证证据**
- `mvn -o -q clean test-compile` 通过（工程根 `backend/marketing`）。
- 两库重建后 `mvn -o test` → **82 tests, 0 failures, 0 errors, 0 skipped**。
  首跑曾因 `bs-nacos-test` 容器退出（exit 139）全红，`dev-stack.ps1 up` 拉起后同一命令全绿——与代码无关。
- 反查：`backend/marketing` 全部 `.java` / `.xml` / `.sql`（排除 `target/` / `logs/` / `docs/`）中
  `virtual_inventory(?!_q)` 与 `VirtualInventory(?!Quota)` 只余
  `IVirtualInventoryService` / `VirtualInventoryServiceImpl` / `queryVirtualInventory` 等**本票有意不改**的名字；
  `target/classes` 只剩 `VirtualInventoryQuotaMapper.xml`。
- `backend/marketing/docs/`、`CONTEXT.md`、ADR 按 map 口径留到 12，本票未动。

## 注意

- **表名改了、前缀没改**：Redis key 仍是旧形状，05 才收敛 key（spec §7.3）。
- 不要为了「对称」把 `physical_marketing_quota` 改成 `physical_inventory_quota`——spec §2.1 明确禁止。
- `@MapperScan` 是逐包列举、按类名生成 bean，改类名不需要动它；若启动报 bean 冲突，先查是否新旧两份 mapper
  XML 同时存在（删干净）。
