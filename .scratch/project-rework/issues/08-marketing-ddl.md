# 票 08 · bs_marketing DDL 落地（22 张照 sql.md §2）+ outbox 形状切换

Type: task
Status: resolved
Blocked by: 07

## 上下文预算（硬约束）

| 项 | 值 |
| --- | --- |
| 目标 | **150–200k**；硬顶 **300k** |
| 预估改文件 | ~12（schema.sql 新建 + init 两份 + xxl seed 一份 + legacy 一份 + 比对脚本 + outbox 组件 ~5 + 测试断言 ~3） |
| 必读 | **`sql.md#L600-L1007`（仅此区间，约 408 行）**；本票；票 05 的 `backend/better-school/sql/schema.sql` 作口径样板 |
| 不读 | `sql.md` 其余章节；rafflex；各域业务代码（本票只碰 outbox 一个公共组件） |
| 必跑 | 建库脚本 + 比对脚本（**不跑业务测试**——本票不迁移任何域的行为） |
| 越界信号 | 想「顺手把 lottery_*/points_* 代码迁到新表」→ 停手，那是票 10/11/13 |

## 已核实事实

1. `sql.md` §2 段落边界（票面已定位，别再全文搜）：**L600 标题 / L606–L727 积分与次数段 / L729–L844 抽奖段 / L846–L898 虚拟库存段 / L900–L989 实物库存段 / L991–L1007 outbox**。
2. **§2 实为 22 张表，但 L602 写「共 21 张表」——文档自身笔误**，需在票内订正回写。
3. 现状营销 SQL 是分开的：`marketing-activity/sql/schema-lottery.sql`（`lottery_*`）、`marketing-asset/sql/schema-points.sql`（`points_lot`/`points_consume`/`points_config`）、`schema-referral.sql`（`referral_invite_code`/`referral_group`），三个文件**各有一张同名 `outbox_message` 但形状不同**（旧列 `topic`，`sql.md` 新形状是 `destination`）。
4. 票 05 已把 `better-school/sql/schema.sql` 定型为「唯一 DDL 来源，`CREATE TABLE IF NOT EXISTS` + 业务列/uk + `create_time/update_time` 口径 + 中文 COMMENT」。营销侧照抄这个口径。
5. 旧 `outbox_message` 的 `topic` 列在测试里被直接断言：`LotteryDrawIntegrationTest`（`WHERE topic = 'bs-lottery-drawn'`）、`PointsApiTestBase`（`WHERE topic = ?`）、`ReferralApiTestBase`（referral，票 09 会删）。

## 票面定：过渡期双 schema（保「每票收口都绿」）

本票**不改任何域的业务代码**，但新表名与旧表名完全不同。为保证票 09–13 期间每票都能跑全量测试，采用过渡方案：

- `backend/marketing/sql/schema.sql` = **完整 22 张新表**（照 `sql.md` §2 逐表）；
- `backend/marketing/sql/schema-legacy.sql` = **尚未迁移的旧域表**，从三个旧 schema 文件原样搬来（`lottery_*`、`points_lot`/`points_consume`/`points_config`、`referral_*`），文件头写明「临时件，各域迁移票负责删自己那几张，清空即删文件」；
- `init-dev.sql` / `init-test.sql` = `CREATE DATABASE bs_marketing ...` + `USE`（与 better-school 同形），建库脚本按 `schema.sql` + `schema-legacy.sql` 顺序灌。
- **outbox 例外**：`outbox_message` 在合并后的库里只有一张，不能新旧并存。故本票同时完成 outbox 的**形状切换**：`OutboxPO`/`OutboxMapper.xml`/`IOutboxDao`/`OutboxMessageStore`/`OutboxMessageSender` 改到新形状（`destination` 等），并同步修上面第 5 条列出的测试断言（referral 那处留给票 09）。
- `migrations/` 目录整体删除（`map.md`：`schema.sql` 是唯一 DDL 来源，改表 = 改 schema.sql + 重建库）。

## Question

按 `sql.md` §2 把营销库 DDL 一次性落地为**单文件 `backend/marketing/sql/schema.sql`（22 张）**，并完成 outbox 形状切换与过渡期 legacy schema，使后续每张域票都能在全量测试绿的基线上开工。

## 施工边界（不做什么）

- 不迁移任何域（points / lottery / referral）的代码与测试到新表；不删 legacy 里的任何表（各域票自己删）。
- 不删 referral 代码（票 09）。
- 不实现任何新行为（库存合并、责任链、桶模型都归后续票）。
- 比对本票**只做 DDL 层**：表名 / 列名 / 类型 / NOT NULL / 默认值 / 键 / 顺序 / COMMENT。

## 验收

1. `backend/marketing/sql/schema.sql` 收录 22 张表，与 `sql.md` §2 逐表逐列一致。
2. 比对脚本可重复跑：扩展 `.scratch/project-rework/cmp_schema.py` 支持第二个库（或新增 `cmp_schema_marketing.py`），输出「逐列全等」或列出差异行。
3. 建库脚本一次通过：`DROP DATABASE IF EXISTS bs_marketing; CREATE DATABASE ...` + 灌 `schema.sql` + `schema-legacy.sql` → 表数 = 22 + legacy 表数；`bs_marketing_test` 同理。
4. `cd backend/marketing && mvn -o test` **仍全量绿**（outbox 形状切换 + 断言联改完成后）。
5. `sql.md` 回写：§2 标题行「共 21 张表」→「共 22 张表」。
6. `## Answer` 记录：22 张表清单、legacy 保留清单（哪几张留给哪张票删）、outbox 列映射（旧→新）。

## Answer

（2026-10-06 完成，`mvn -o test` 87 用例全绿）

**22 张新表清单**（`backend/marketing/sql/schema.sql`，与 sql.md §2 逐表逐列全等，比对脚本 `.scratch/project-rework/cmp_schema_marketing.py` 输出「22 identical · 逐表逐列全等」）：
积分与次数段 7 张：`points_bucket` / `points_detail` / `draw_count_bucket` / `draw_count_detail` / `points_validity_config` / `draw_count_config` / `action_rebate_config`；抽奖段 6 张：`draw_activity` / `draw_strategy` / `draw_strategy_award` / `strategy_weight` / `draw_record` / `draw_record_detail`；虚拟库存 3 张：`virtual_inventory` / `virtual_inventory_bucket` / `virtual_inventory_detail`；实物库存段 5 张：`physical_inventory` / `physical_inventory_detail` / `physical_marketing_quota` / `physical_marketing_quota_bucket` / `physical_marketing_quota_detail`；`outbox_message`（新形状）。

- `strategy_weight` 是 sql.md §2 里唯一的设计稿原样表（反引号 + `create_time/update_time` 带 `DEFAULT CURRENT_TIMESTAMP`/`ON UPDATE`），为保逐列一致照抄未按本库收尾口径改写——schema.sql 内已留注释，cmp 脚本对类型关键字做大小写归一（MySQL 语义等价）。
- 比对口径：表名/列名/类型/NOT NULL/默认值/键/顺序/列 COMMENT；`IF NOT EXISTS`、反引号、表级尾选项（ENGINE/CHARSET/表 COMMENT）不在轴内，归一化剥掉。

**legacy 保留清单**（`backend/marketing/sql/schema-legacy.sql`，15 张 + 各自种子原样自三个旧 schema 文件搬来；文件头写明删除责任）：
| 旧表 | 删除票 |
| --- | --- |
| `referral_invite_code` / `referral_group`（2） | 票 09 remove-referral |
| `points_lot` / `points_consume` / `points_config`（3） | 票 10 points-bucket-model |
| `lottery_*` 十张（含 `lottery_activity` + slogan 守卫 + 奖品种子） | 票 11 落新表（legacy 不动）、票 13 lottery-draw-pipeline 删 |

过渡期 `bs_marketing` / `bs_marketing_test` 各 37 张 = 22 新 + 15 旧；`init-dev.sql` / `init-test.sql` 改为与单体同形（`CREATE DATABASE` + `USE`），`scripts/test-env.sh` 按 `schema.sql` → `schema-legacy.sql` 顺序灌；`schema-points/lottery/referral.sql` 与 `migrations/022–024` 删除。

**outbox 列映射（旧 → 新）**：`topic` → `destination`（语法 topic / topic:tag，存量 topic 值即合法 destination，业务发消息的三个 topic 常量不变）；`status` → `state`；`created_at` → `create_time`；`updated_at` → `update_time`。联改面：`OutboxPO` / `OutboxMapper.xml` / `OutboxMessageStore.save(destination,…)` / `OutboxMessageSender`（`getDestination`）；测试断言 `LotteryDrawIntegrationTest`、`PointsApiTestBase.outboxPayloads`、`ReferralApiTestBase.groupedEventPayloads` 三处 `WHERE topic =` → `WHERE destination =`。

- ⚠️ 票面说「referral 那处断言留给票 09」——但 `topic` 列随本票消失，留着必挂测试、违背验收 4 的全量绿，故本票做了最小联改（只换列名），文件整体删除仍归票 09。

**验证**：`test-env.sh up` 建库一次通过（两库各 37 表、outbox 新形状列序核对无误）；cmp 脚本 22/22 全等；`cd backend/marketing && mvn -o test` 87/87 绿。

**连带影响清单（未处理，归后续票）**：`sql.md` L4「尚未落库」句与 L602 来源句仍指旧三份 schema 文件（本票只按票面订正了「21→22」）；`backend/marketing/docs/db/{points,lottery,referral}.md`、`backend/docs/db/README.md`、`backend/docs/architecture/marketing-services.md`、`backend/docker-compose.yml` 头注释、`backend/CLAUDE.md`（引用营销旧 schema 布局处，code-review 补记）仍指旧 schema 布局 → 归票 18 final-docs；`xxl-job-tasks.sql` 无需改动（handler 名与 cron 不受形状切换影响）。

<!-- 收口时补：Context used: ~130k；未触发拆分；连带影响清单见上 -->
