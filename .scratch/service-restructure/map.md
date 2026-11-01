# Map：服务结构与目录重构（backend 三服务收敛）

## Destination

一份《服务结构与目录重构 spec》（`.scratch/service-restructure/spec.md`）：目标目录结构、模块与包名映射、搬运/联改清单、验收标准。本 effort 不动代码——spec 定稿后另起 impl effort 执行。

> **✅ 已达成（2026-09-17）**：两张票全 resolved，spec 已定稿；后续执行票由 to-tickets 另起 effort。

## Notes

- 领域文档：根 `CONTEXT.md`（词汇表）；后端编码规范：`backend/CLAUDE.md`（七模块 DDD 骨架，迁移后仍留在 backend 根）。
- 本次 charting 会话已把主要决策当面敲定，快照如下（细节以本节为准，spec 起草票不得推翻，只能补充）：
  1. **布局**：`backend/` 保留，下设 `better-school/`、`marketing-activity/`、`marketing-asset/` 三个 Maven 工程。
  2. **主服务**：现 bs-* 七模块（types/api/domain/case/infrastructure/trigger/app）原样搬入 `better-school/`，模块名、内部结构、包名（`com.betterschool.*`）全部不动。
  3. **营销活动服务**：lottery + referral 收敛成主服务式一套模块，模块前缀 `marketing-activity-`（见名知意长前缀）；domain 模块下 `lottery` / `referral` 两个领域包；共享一个 app 启动器。
  4. **营销资产服务**：points 同样收敛成主服务式一套模块，前缀 `marketing-asset-`；domain 下 `points` 领域包。
  5. **包名**：营销侧重命名，方案 A——活动服务包根 `com.betterschool.activity`（如 `activity.domain.lottery.*`、`activity.api.ILotteryService`），资产服务包根 `com.betterschool.asset`（如 `asset.domain.points.*`、`asset.api.IPointsExchangeService`）。（⚠️ 票 02 细化：lottery/referral 同名类冲突，activity 合并后**全五层带域子包**，api 示例相应为 `activity.api.lottery.ILotteryService`，见票 02 Answer）
  6. **依赖方向（现状已单向，重构后保持）**：activity → asset-api + bs-api（用户查询）；asset → bs-api；bs-* 对营销零依赖。跨服务 api 引用随包名联改（如 activity 侧 `PointsExchangePort` 改引 `com.betterschool.asset.api.*`）。Dubbo 接口 FQN 随包名变化，无生产流量、无过渡需求，直接切换；前端走 HTTP，Controller 的 URL 映射字符串不变。
  7. **公共物**：docker-compose、跨服务脚本留 `backend/` 根；sql / docs / 配置按服务拆进各工程；`backend/CLAUDE.md` 留根；`logs/` 清理。明细见票 01 产出。
  8. **仓库根杂物**：根目录图片（`*.jpg`/`*.png`）、`实现方案3.md`、`评论/` 归档进 `docs/`；`vite-*.log` 删除并加 .gitignore；根 `CLAUDE.md`、`CONTEXT.md`、`docs/`、`.scratch/`、`front/`、`agent/` 不动。（⚠️ 票 02 核实：全局 `*.log` 已在 .gitignore，无需新增，只删文件）
- spec 必须覆盖的补充决策（起草时定，不必单独开票）：三个服务的端口分配；activity 两个 app 的 application.yml / nacos / xxl-job 配置合并方案。
- 工作流偏好：本用户固化 grilling→spec→tickets 流程；spec 定稿后由 to-tickets 另起 effort，本 map 不产出执行票。

## Decisions so far

- **[票 01 · backend 根公共物盘点](issues/01-inventory-backend-commons.md)**（细节在该票 `## Answer`）：sql 按服务拆（`xxl-job.sql` 留根）、`test-env.sh` 留根、compose/`docker/es`/`broker.conf` 留根、`backend/Dockerfile` 归 better-school、docs 单体段/营销段拆、`logs/` 删 `.idea` 保持忽略；跨服务引用共 4 个 Port 文件 + 4 条 pom 依赖（bs-* 零依赖营销，grep 证实），referral 直读 `bs_lottery.lottery_activity` 收敛后自然消解；四 app 配置现状表（端口/dubbo/xxl/producer group）已备好供 spec 直接抄。三个争议点移交票 02：init-marketing 拆分方式（随 activity 库合并）、xxl 营销任务注册落点、front Vite 代理与测试基类端口联动。**票 02 已解锁**。
- **[票 02 · spec 起草定稿](issues/02-draft-restructure-spec.md)**（细节在该票 `## Answer`）：[`spec.md`](../spec.md) 已定稿（2026-09-17 用户审阅，Destination 达成）。库名定 `bs_activity`/`bs_asset`（本地重建）；activity 包名**全五层带域段**（同名类冲突所迫，细化 Notes 决策 5 示例）；端口沿用 asset 8081/activity 8082、弃 8083；xxl 执行器二合一，`outboxScan` 冲突改名 `lotteryOutboxScan`/`referralOutboxScan`；consumer group 常量值不改；`.gitignore` 无需改。拆票建议五张（见 spec §7）。

## Not yet specified

（暂无——当前所有能想到的问题都已收进 Notes 快照或两张票。若票 01 盘点发现新的归属疑点，毕业为新票。）

## Out of scope

- **部署编排**：docker-compose 增加三个服务容器定义、一键起全栈——另起小 effort，不属于"理结构"。
- **代码逻辑变更**：本次只动结构（目录/模块/包名/配置），不改任何业务行为。
