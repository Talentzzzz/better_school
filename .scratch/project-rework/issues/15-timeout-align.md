# 票 15 · 超时中心对齐 sql.md（bs_timeout + 注册 outbox）

Type: task
Status: resolved
Blocked by: （无）

## 上下文预算（硬约束）

| 项 | 值 |
| --- | --- |
| 目标 | **≤150k**；硬顶 **300k** |
| 预估改文件 | ~10（timeout 侧 DDL/装配 + 单体侧 `timeout_register_outbox` 核对） |
| 必读 | **`sql.md#L1011-L1035`（§3，仅此区间）** + **`sql.md#L198-L234`（`outbox_message` + `timeout_register_outbox` 两表）**；本票 |
| 不读 | `sql.md` §1 其余 27 张表（票 05 已收口）、§2 营销段 |
| 必跑 | `backend/timeout-center && mvn -o test`；`backend/better-school` 超时相关用例 |
| 越界信号 | 想「顺手把 §1 其他表再对一遍」→ 不需要，票 05 已逐表校验过 |

> 本票**独立于营销链**（不同服务、不同库、不同测试库），可在票 06–14 任意空档插空做；它不是「小票」里可以省掉的那张——它是全程唯一一条随时可开工的线。

## 已核实事实

1. `sql.md` §3 = **L1011–L1035**，1 张表 `timeout_task`；文中注明「本文档收录分库分表之前的单表形态；落地分表后拆 16 张同构表，业务 SQL 仍写逻辑表 `timeout_task`」。现状 `backend/timeout-center/sql/schema-timeout-task.sql` 自述**已是分库分表落地形态**。
2. `sql.md` §3 的关键口径：`uk_type_biz` 是全局幂等键；单表形态下扫描退化为一条 `idx_scan` 区间查询；`state` = `WAITING/RETRYING/SUCCESS/FAILED/CANCELED`；重试 16 次封顶。
3. 单体侧 `timeout_register_outbox` 在 `sql.md` 的 **L216–L234**；票 05 已把 `better-school/sql/schema.sql` 收到新口径（含 `bindBizId` 与 `type` 列口径）。
4. 「多数代码应已就位」（timeout-center 自己的票 12 刚收口）：本票以**对账查缺**为主，规模预期小。

## Question

timeout-center 与单体注册边对齐 `sql.md`：

- **DDL**：`backend/timeout-center/sql` 按 §3 核对/重写；**评估现状 16 张分表形态是否已满足 sql.md 口径**——满足则只补文档说明，不满足才落分表（结论与理由写进 `## Answer`）。
- **注册边**：`timeout_register_outbox`（better-school 库）的列名/口径与 §1 L216–L234 对齐；确认 **Dubbo 回调链路不变**。
- `init-dev.sql` / `init-test.sql` 同步；相关测试改绿。

## 施工边界（不做什么）

- 不动营销三服务（票 06–14）。
- 不重做 §1 其余表（票 05 已收口）。
- 不改回调协议语义。

## 验收

1. `backend/timeout-center && mvn -o test` 全量绿。
2. `backend/better-school && mvn -o test` 全量绿。
3. 逐表比对：`timeout_task`（§3）与 `timeout_register_outbox`（§1 L216–L234）列/键/默认值全等，或列出「已满足 / 已改」的差异表。
4. `## Answer` 记录：分表形态结论（现状是否已满足 + 证据）、注册边核对差异、改动文件清单。
5. `map.md` 回写：本条结论一行。

## Answer

**结论一句话**：本票**零行为变更**——两张表在**物理 DDL 上已全等**（16 张分表形态是「已满足」，不重建分表）；要动的是 `sql.md` §3 的**文字口径**（它是分表前的草案，与落地列名 / 唯一键列序不一致），已照落地代码回写。

### 1. 分表形态结论：现状**已满足** sql.md 口径（不动 DDL）

`backend/timeout-center/sql/schema-timeout-task.sql` **已是分库分表落地形态**（16 张 `timeout_task_00..15`），**不需要**「落地分表」这一步：

| 判据 | 证据 |
| --- | --- |
| 16 张同构分表在库 | `bs_timeout_test` 里 `information_schema.tables` where `table_name like 'timeout_task_%'` = **16**；dev 库同（`test-env.sh up/reset-test-db` 的 `apply_service_init` 走的就是这份 DDL） |
| 分片真在生效 | `sharding.yaml` 逻辑表 `timeout_task` → `actualDataNodes` 列 16 张物理表 + `CLASS_BASED/HINT` 算法；`TimeoutTaskRepository` 每次 DAO 调用前塞 `HintManager`（`TimeoutTaskShardSlots` 单点公式） |
| 逻辑表名才是业务面 | `TimeoutTaskMapper.xml` 全部 SQL 写 `timeout_task`（无一处物理表名）；`sharding.yaml` 头注释、`docs/db/timeout-task.md` §分片路由 逐条对齐 |
| 跨库事务问题 | 一张分表的全部读写同库同表；`DB_COUNT = 1` 起步（16 槽同库），扩库只改配置 |

**只补文档说明，未落/未拆任何分表**。`sql.md` 侧共 4 处文档改动：① §3 整体回写；② §3 末尾加「业务/运维 SQL 只写逻辑表名、别写 `timeout_task_07`」的告警；③ 文首命名口径行 L5 登记唯一例外；④ §1 `timeout_register_outbox` 后加「两侧列名故意不同名」注。DDL 侧 1 处：`schema-timeout-task.sql` 头注释加「库表唯一事实源 = 本文件，改 DDL 必须同批回写 `sql.md` §3」（纯注释 +3 行，DDL 一字未动）。

### 2. 逐表比对（列 / 键 / 默认值）

**`timeout_task`（§3）——列全等，5 处文字口径差异（DDL 未改，改的是 sql.md）**

| 项 | sql.md §3（改前，分表前草案） | 落地 DDL（未改） | 处置 |
| --- | --- | --- | --- |
| 任务类型列 | `type` | `task_type` | **sql.md 改**（含 §3 正文与唯一键说明） |
| 状态列 | `state` | `status` | **sql.md 改** |
| 时间列 | `create_time` / `update_time` | `created_at` / `updated_at` | **sql.md 改** |
| `uk_type_biz` 列序 | `(biz_id, type)` | `(task_type, biz_id)` | **sql.md 改**——与路由键同序、也是所有按键语句（`WHERE task_type = ? AND biz_id [= ? \| IN(...)]`）的谓词顺序，即索引前缀 |
| 表头 / 形态 | 「收录分表之前的单表形态」 | 16 张物理同构表 | **sql.md 改**：改为「逻辑表 = 16 张物理同构表」，并写清为什么是 16 张、为什么逐张写出 |
| 文首命名口径 L5 | 「**全库**不再出现 `created_at` / `updated_at`」 | 16 张分表用的就是 `created_at`/`updated_at` | **sql.md 改**：登记为唯一例外（见下「比对时多抓到的一处」） |
| 其余 9 列 + 2 键 | 见下 | 同 | **全等，未改** |

**比对时多抓到的一处（票面没预见、但本票必须定）**：`sql.md` 文首 L5 的命名口径写「每张表固定以 `create_time`/`update_time` 收尾，**全库**不再出现 `created_at`/`updated_at`」——这与 §3 落地形态**直接矛盾**（实测：§1 单体 29 张 + §2 营销 22 张 = **51 张全守这条口径**，`timeout_task` 是 52 张里的唯一例外）。处置：**登记为例外并写进两处**（文首口径行 + §3 变更说明）。
**为什么不改名对齐**：`created_at`/`updated_at` 已进 16 张分表 + `TimeoutTaskMapper.xml` 全部 SQL，改名要重建 16 张表并改 XML 与 DDL；而收益只是命名整齐，且 §3 的列名口径本来就是「照落地代码」定的。这是**有意识的偏离登记**，不是漏改。

其余列逐字全等：`id BIGINT NOT NULL AUTO_INCREMENT`、`callback_group VARCHAR(64) NOT NULL`、`biz_id VARCHAR(64) NOT NULL`、`action_time DATETIME NOT NULL`、`next_fire_time DATETIME NOT NULL`、`retry_count INT NOT NULL DEFAULT 0`；索引 `UNIQUE uk_type_biz` / `KEY idx_scan (status, next_fire_time)`（列序也对）；表注释、`ENGINE`、字符集全等。状态枚举 `WAITING/RETRYING/SUCCESS/FAILED/CANCELED` 与 `TaskStatus` 枚举逐项对得上，16 次封顶也一致。

**机读复核**（从 `sql.md` 正则取 DDL 块，与 `information_schema` 实测逐项比，不靠人眼）：

| 表 | 列数 | 列序全等 | 索引 |
| --- | --- | --- | --- |
| `timeout_task`（§3 vs `bs_timeout.timeout_task_00`） | 10 = 10 | ✅ | ✅ `PRIMARY(id)` / `uk_type_biz(task_type,biz_id)` / `idx_scan(status,next_fire_time)` |
| `timeout_register_outbox`（§1 vs `better_school_test`） | 9 = 9 | ✅ | ✅ `PRIMARY(id)` / `uk_type_biz(type,biz_id)` / `idx_scan(state,create_time)` |

**`timeout_register_outbox`（§1 L216–L234）——零差异**

| 项 | sql.md §1 | `schema.sql` L172–185 / `bs..._test` 实测 `information_schema` | 处置 |
| --- | --- | --- | --- |
| 列（9） | `id, type, callback_group, biz_id, action_time, state, retry_count, create_time, update_time` | 同，类型 / NULL / 默认值（`state='PENDING'`、`retry_count=0`）逐项相同 | 无 |
| 唯一键 | `uk_type_biz (type, biz_id)` | 同（`SEQ_IN_INDEX` 实测 1=`type`、2=`biz_id`） | 无 |
| 扫描索引 | `idx_scan (state, create_time)` | 同 | 无 |
| 表注释 / 引擎 | 同 | 同 | 无 |

票 05 的收口在本表上被实测确认（`schema.sql` 与 `sql.md` 逐字对齐）；票面点名的 `bindBizId` 是 §1 其他表的收口产物，与本表无关。`TimeoutRegisterOutboxMapper.xml` 的 SQL 列名（`type` 等）与 PO / DAO 参数名（`taskType`）的差异是**参数名 vs 列名**，mapper 注释已写明，未改。

**两表列名「不一致」是刻意的**：注册边在业务库、中心在 `bs_timeout`，跨库不 join，各自照本库命名口径（业务库统一 `type`/`state`/`create_time`，中心统一 `task_type`/`status`/`created_at`）。已在 `sql.md` 两处各写一句「不要为『对齐』把两侧改成同名」，防止下一轮被误"修"。

### 3. 注册边 / 回调链路：**语义未动**

- 注册方向：`ITimeoutTaskRegisterPort`（`@DubboReference`）→ `ITimeoutTaskRegister.register(List<TaskRegisterItemDTO>)`，`TimeoutRegisterOutboxSender`（提交后即时虚拟线程发送 + `@XxlJob("timeoutRegisterOutboxScan")` 30s 扫描兜底）**一行未改**。
- 回调方向：`TimeoutTaskCallbackPort`（按 `callback_group` 动态建 Dubbo 引用）→ `ITimeoutTaskCallback.onTimeoutBatch`，DTO 仍是 `taskType/bizId/actionTime` **三字段、不带快照**——**一行未改**。
- 本票唯一涉及接口的地方是「核对」，核对结果是**不需要改**（施工边界里「不改回调协议语义」自然满足）。

### 4. 改动文件清单（5 个：4 个文档 + 1 个 DDL 文件的注释）

| 文件 | 改动 |
| --- | --- |
| [`sql.md`](../../../sql.md) | §3 `timeout_task`：表头形态说明 + DDL 块按落地列名 / 键列序回写 + 新增「单表→分表口径变更」/「为什么是 16 张」/「只写逻辑表名」说明（`### timeout_task` 标题按本仓 51 张表的一致体例保留）；**文首命名口径行 L5 登记 `timeout_task` 为 `created_at`/`updated_at` 的唯一例外**；§1 `timeout_register_outbox` 后加「两侧列名故意不同名」注 + 该表 `type` 列注释改为「取值域与中心侧 `task_type` 同一套」（消歧义） |
| [`backend/timeout-center/sql/schema-timeout-task.sql`](../../../backend/timeout-center/sql/schema-timeout-task.sql) | **仅头注释**：声明「库表唯一事实源 = 本文件，改 DDL 必须同批回写 `sql.md` §3」+「一份 DDL × 16 为何逐张写出」+ 槽位公式改用真写法。**16 张表的列名 / 类型 / 键 / 默认值一字未动** |
| [`backend/better-school/sql/schema.sql`](../../../backend/better-school/sql/schema.sql) | **仅 `timeout_register_outbox.type` 的列注释文字**（「与中心侧一致」→「取值域与中心侧 `task_type` 同一套」，原措辞会被读成「列名一致」，与 §3/§1 的「故意不同名」注冲突）。**列名 / 类型 / 键 / 默认值一字未动** |
| [`map.md`](../../map.md) | 票 15 结论一栏 + 行区间索引（`sql.md` 1051 行、§3 实测 L1012–L1051）+ 加「行号会随票漂移，优先用 § 标题定位」告警；Not-yet-specified 记一条「槽位公式注释漂移，另 4 处未清」 |
| 本票文件 | `Status: claimed` → `resolved`，本节 |

两份 `init-dev.sql` / `init-test.sql` **无需改**（只建库、不含表；已实测：`init-{dev,test}.sql + schema-timeout-task.sql` 拼给同一连接重跑 exit 0、16 张表在）。

### 5. 测试

| 范围 | 结果 |
| --- | --- |
| `backend/timeout-center && mvn -o test`（重建测试库**之前**） | **37 绿**（`BUILD SUCCESS`，`Failures: 0, Errors: 0`） |
| `backend/better-school && mvn -o test`（重建测试库**之前**） | **230 绿**（`BUILD SUCCESS`，含 `TimeoutRegisterOutboxIntegrationTest` 3、`TimeoutTaskCallbackIntegrationTest` 5） |
| 两套**重建测试库之后**再各重跑一次 | **37 绿 / 230 绿**，均 `BUILD SUCCESS` |

**测试库处理（照 backend/CLAUDE.md §10 重建，只是换了执行器）**：`scripts/test-env.sh` 在本机不可用（只有 WSL 的 `bash.exe`，`/bin/bash` 不存在），所以按脚本第 143–163 / 126–134 行的等价 SQL 用 PowerShell 执行了同一套动作：**DROP + 重建 `better_school_test` 与 `bs_timeout_test`**（各 `init-test.sql + schema-<svc>.sql` 拼给同一连接）→ 两次 **exit 0**，重建后 `timeout_task_%` = **16 张**、`better_school_test` 表数 = **29**（与 `schema.sql` 的 29 张一致）。重建后**两套测试各重跑一次全绿**，即跑的就是当前工作区里这份 DDL 建出来的库。

> 这一步是必须的、不是仪式：本票把 `timeout_register_outbox.type` 的 **COMMENT 改了字**（`CREATE TABLE IF NOT EXISTS` 不改已存在表 ⇒ 不重建就永远是旧注释）。重建后实测库里就是新注释「任务类型（业务域前缀命名，取值域与中心侧 \`task_type\` 同一套）」。同时顺手验了 COMMENT 字面量里的反引号是**合法**的（MySQL 允许，实测建表 exit 0、注释原样存入）。

**两处票面字面的落空（留痕）**：
1. 票面验收 3 的「列/键/默认值全等，**或**列出『已满足 / 已改』的差异表」——本票走的是后一条：**物理 DDL 侧全等、0 处已改**，差异全在 `sql.md` 的文字口径（上表 5 处）。
2. 票面 Question 的措辞是「满足则只补文档说明」——本票的「补文档」额外包含**回写 `sql.md` 本体**（不只加说明）。这是照 map.md L84 的口径「`sql.md` 是库表唯一事实源；发现对不齐之处**在票内定并回写 `sql.md`**」+ 票 11 先例（`award_decr`→`award_desc`）做的；不这么做的话，`sql.md` §3 会继续向票 17/18 描述一个不存在的列名集合。反方向（把 16 张分表的 `task_type/status/created_at/updated_at` 改成 §3 草案的 `type/state/create_time/update_time`）才是破坏性 DDL：要重建 16 张表 + 改 `TimeoutTaskMapper.xml`，与「不改回调协议语义 / 已满足则只补文档说明」冲突，故未做。

**code-review 双轴复核（Standards + Spec 并行子代理）抓到并已修的问题**：
- `sql.md` §3 **丢了 `### timeout_task` 标题**（回写时被改成散文，成为全文件 51 张表里唯一没有表标题的一节，且 map 索引引用的就是它）→ 已按体例补回。
- 我在 §3 写的槽位公式 `hash(task_type + biz_id) % 16` 与同段末尾 `TimeoutTaskShardSlots.slotOf` 的**真公式不同**（真公式带长度前缀）→ 已统一成 `floorMod(hashCode(task_type.length() + ":" + task_type + biz_id), 16)`。
- 「`uk_type_biz` 列序与分片函数前缀同序」是**错误归因**（`hashCode` 与索引列序无前缀关系）→ 改为「与路由键同序 + 是所有按键语句的谓词顺序」。
- 结论里「§1/§2 52 张表…53 张里的唯一例外」**计数错**（实测 29 + 22 = 51，共 52）→ 已改。
- 改动文件清单漏了 `map.md`（验收 5 要求的那个）→ 已补全为 5 个文件。
- `map.md` 自己的行区间索引因本次改动失真（「全文 1035 行」、§3「L1011–L1035」）→ §3 行与行数已实测更新为 1051 行 / L1012–L1051，并加「行号会随票漂移，优先用 § 标题定位」告警（§1/§2 的其余行号差 +2～+3，源头在票 05/08，不在本票，未越界重排）。
- Spec 轴另记两条**未改的**判断：把两处运营性说明（逻辑表名告警、槽位公式）写进 `sql.md` 与 `docs/db/timeout-task.md` §分片路由有重叠（保留，因为 `sql.md` 是「只读自己那一段」的入口，该告警必须在这一段里自足）；本票只回写了 §3 与 §1 的一行注，§1 其余 27 张表按票面边界未动。

<!-- 收口时补：Context used: ~120k（含 code-review 双轴复核）；未触发拆分；连带影响清单：无代码影响；sql.md §3/§5 与 map 行区间索引已同批更新，票 17/18 读 sql.md 请用 § 标题而非死行号 -->

