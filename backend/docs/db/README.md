# 库表总览

单体收口后全库只有 **`better_school`**（测试 `better_school_test`）：营销四域（points / draw（含抽奖次数）/ rebate / award）的 20 张表随 monolith-restructure 票 07 并入单体
`backend/better-school/sql/schema.sql` **尾段**，该文件是全库唯一 DDL 事实源（72 张表，含 16 张 `timeout_task` 分表；
单份 `outbox_message` DDL——取 marketing 超集形状，含 `delay_level` / `delay_ms`）。
`bs_marketing` / `bs_marketing_test` 库名退役。

| 域段 | 内容 | 表文档 |
| --- | --- | --- |
| 单体平铺域（社区 / 用户 / 钱包 / chat / 通知 / 交易 / 信用分 / 超时 / outbox） | 33 张业务表 + 16 张 `timeout_task` 分表 | [`../../better-school/docs/db/README.md`](../../better-school/docs/db/README.md) |
| 营销并入域（points / draw / rebate / award） | 20 张表（含与单体合流的 outbox） | [points](../marketing/db/points.md) / [draw](../marketing/db/draw.md) / [award](../marketing/db/award.md) |

- **跨域纪律不因合库改变**：一个物理事务只写一个仓储（一个域）的表（ADR-0016）；
  用户表归 user 域独占写，营销域只存 `user_id`；跨域读写一律由 case 层编排直调他域 service，
  domain 不再使用任何跨域 port。
- **SQL 层零 JOIN**：同库内跨表展示的数据（如抽奖的中奖记录关联 `award_inventory`
  带出面额与库存）一律多查一次应用层拼装。
- 建库/重建命令见 `scripts/test-env.sh`，Windows 上 `scripts/dev-stack.ps1 up`（`-ResetDb` 重建开发库）。
