# 库表总览

单体库 `better_school` 总览已随 better-school 工程落位（`backend/better-school/docs/db/README.md`）。

| 库 | 归属 | DDL 来源 | 表文档 |
| --- | --- | --- | --- |
| `bs_marketing` | 营销服务（points / drawcount / config / raffle / inventory 五段同库；票 06 并入积分、票 07 并入抽奖、票 13 抽奖改 `raffle` 域段） | `marketing/sql/init-dev.sql` + 同目录 `schema.sql`（**唯一 DDL**，18 张；`schema-legacy.sql` 票 13 已删） | [points](../../marketing/docs/db/points.md) / [draw](../../marketing/docs/db/draw.md) / [inventory](../../marketing/docs/db/inventory.md) |

- **开发/测试库名隔离**：营销 `bs_marketing` 及 `bs_marketing_test`（测试栈 host 端口 +10000）。
- **营销库的跨库纪律**：与单体库之间只存 id、**不 join 单体库、不存昵称头像快照**，
  用户信息一律经 Dubbo 取回后在应用层拼。
  同库内需要跨表展示的数据（如抽奖的中奖记录关联 `virtual_inventory_quota` 带出面额与库存）一律**多查一次应用层拼装**，SQL 层零 JOIN。
- 建库/重建命令见 [`../../docker-compose.yml`](../../docker-compose.yml) 头注释 1，或直接 `scripts/test-env.sh up`。
