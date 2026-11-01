# 02 起草《服务结构与目录重构 spec》

Type: task
Status: resolved
Blocked by: 01

## Question

按 map Notes 里的决策快照（1–8，不得推翻）+ 票 01 的归属清单，起草 `.scratch/service-restructure/spec.md`，内容必须包含：

1. **目标结构**：`backend/` 最终目录树（三个工程 + 根留公共物），三个服务各自的模块清单（主服务七模块原名；营销两服务 `marketing-activity-*` / `marketing-asset-*` 模块组）。
2. **映射表**：旧位置 → 新位置，逐模块/逐目录；包名映射（方案 A：`com.betterschool.activity` / `com.betterschool.asset`）；sql/配置/文档按票 01 清单落到工程。
3. **联改清单**：包名重命名的波及面（import、Dubbo FQN、nacos 配置、MyBatis mapper 路径、xxl-job 任务配置）；跨服务引用改写点（activity 引 asset-api、营销引 bs-api——bs-api 本身不动）。
4. **补充决策**（起草时给出建议值）：三服务端口分配；activity 服务合并 lottery-app 与 referral-app 配置的方案（application.yml、nacos、xxl-job executor）；启动类根包 `com.betterschool.activity` / `com.betterschool.asset`。
5. **仓库根杂物归置**：按 Notes 决策 8 列明细。
6. **验收标准**：三个工程各自 `mvn compile` / 全量测试通过；三服务可启动、Dubbo 注册与 HTTP 接口行为不变（前端无感）；引用旧包名的残留为零。
7. **执行顺序建议**：给后续 impl effort 的票拆分建议（哪步先做、哪些可并行），但本 spec 不产出正式执行票（to-tickets 另起）。

本票 HITL：spec 初稿完成后交用户审阅定稿，定稿即 map 的 Destination 达成。

## Answer

（2026-09-17 收口。spec 初稿起草 → 用户审阅 → 四项分叉确认 → 定稿：`.scratch/service-restructure/spec.md`。map 的 Destination 达成。）

### 起草中核实的新事实（票 01 之外，grep 证实，已吸收进 spec §0/§3）

1. **xxl handler 冲突**：lottery 与 referral 的 `OutboxMessageSender` 各自注册 `@XxlJob("outboxScan")`，收敛成一个执行器后重名 → 改名 `lotteryOutboxScan` / `referralOutboxScan`（asset 侧唯一保留 `outboxScan`）。
2. **同名类冲突**：lottery/referral 的 `GlobalExceptionHandler`、`PingController`、`SecurityUtils`、`OutboxMapper.xml`、api 层 `UserBasicResponseDTO`/`PageResult`/`Response`、types 层 `Constants`/`OutboxStatus`/`ResponseCode`/`AppException` 同名 → activity 合并后**所有五层带域子包**（map 决策 5 示例 `activity.api.ILotteryService` 细化为 `activity.api.lottery.ILotteryService`，非推翻）。
3. 三营销服务 `*-case` 均为空壳（无 src），合并后各留一个空壳模块。
4. 营销 handler 的 cron 无本仓落点（票 01 已注），spec 给了建议值并标注 impl 时以 admin 现配置核对。
5. `.gitignore` 全局 `*.log` 与 `backend/*/*/target/` 已覆盖 Notes 决策 8 与迁移后路径——**无需改 .gitignore**（Notes"加 .gitignore"经核实已存在）。

### 补充决策定稿（spec §4 十项；用户确认的四项分叉 + 其余建议值）

| 项 | 定稿值 |
| --- | --- |
| 库名 | `bs_activity` / `bs_asset`（含 `_test`），弃三旧库名，本地一键重建（用户确认） |
| activity 包名 | 全五层带域段 `activity.{layer}.{lottery\|referral}.*`（用户确认） |
| asset 包名 | map 字面：domain 带 `points` 段，其余层 `asset.{layer}.*` |
| 端口 | asset 8081/50052/9998、activity 8082/50053/9997，弃 8083/50054/9996（用户确认） |
| 架构文档 | `docs/architecture/marketing-services.md` 留根单份改写两服务口径（用户确认） |
| 其余 | 聚合 pom artifactId `better-school`；启动类 `MarketingActivityApplication` / `MarketingAssetApplication`；producer group `marketing-activity-producer` / `marketing-asset-producer`；**consumer group 常量值不改**（改名有重放风险）；`spring.application.name` = 工程名；xxl title `mktActivity`/`mktAsset`（限 12 字符）；`referral.activity-config-table` 保留、值改 `lottery_activity` |

### 票 01 三个争议点的落点（spec 内）

1. `init-marketing-{dev,test}.sql` → 拆成 activity / asset 各自的 `init-{dev,test}.sql`（§2.3）。
2. xxl 营销任务注册 → 首次落 SQL：activity 4 handler、asset 2 handler，执行器 `marketing-activity-executor` / `marketing-asset-executor`（§3.1-D）。
3. front Vite 代理 / 测试基类端口联动 → `/api/referral` 目标 8083→8082，测试注入随新端口与库名全改（§3.3）。

### 拆票建议（spec §7，正式票由 to-tickets 另起）

根杂物归档（独立）→ better-school 平移（独立，可并行）→ marketing-asset 收敛（先做，含 lottery 侧两条联改保编译绿）→ marketing-activity 收敛（依赖 asset）→ 收尾验收（test-env 6→4 库、docs 改写、§6 七项验收）。asset 与 activity 两票都动营销目录，不可并行。
