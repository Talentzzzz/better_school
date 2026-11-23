# 接口总览

单体七篇接口文档已随 better-school 工程落位（`backend/better-school/docs/api/`，总览见彼处 README）。
ID 口径随 uuid-biz-id 收口：**全部业务 id 字段均为 UUID 字符串（VARCHAR(36)）**，示例见彼处 README 头注。

## 营销域（并入单体，HTTP 8080）

> 营销四域随 monolith-restructure 票 05/07 并入单体（原 `backend/marketing/docs/api/` 迁至
> `backend/docs/marketing/api/`；HTTP 路径前缀不变：`/api/points`、`/api/lottery`、`/api/marketing/*`
> 都走单后端 **8080**）。营销侧 `recordId` / `prizeId` / `refId` / `redeemOrderId` 同为 UUID 字符串。
