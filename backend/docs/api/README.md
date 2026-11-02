# 接口总览

单体七篇接口文档已随 better-school 工程落位（`backend/better-school/docs/api/`，总览见彼处 README）。
ID 口径随 uuid-biz-id 收口：**全部业务 id 字段均为 UUID 字符串（VARCHAR(36)）**，示例见彼处 README 头注。

## 营销服务（8081）

> 资产（积分）段已随票 06 收敛进 `backend/marketing/docs/api/`；
> 抽奖篇已随票 07 并入 `backend/marketing/docs/api/`（HTTP 路径前缀不变：`/api/points`、`/api/lottery` 都走 **8081**；
> 票 09 起营销只余这两域）。营销侧 `recordId` / `prizeId` / `refId` / `redeemOrderId` 同为 UUID 字符串。
