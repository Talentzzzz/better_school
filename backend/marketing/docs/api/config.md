# 营销配置后台接口（/api/marketing/config）

桶模型配套的后台管理面（票 10）：积分/次数有效期 + 行为返利规则。
读接口需登录；写接口（PUT/POST）另有 ADMIN 校验（控制器内 role 判断；票 13 前照 lottery 的 openBucket，
该端点已随旧奖池桶模型删除，这里的 role 判断是现存的 ADMIN 校验先例）。
返回统一 `Response{code,info,data}` 信封。

## GET /points-validity

- Auth: 需要登录
- 成功: `{ pointsValidDays }`（points_validity_config 单行，种子 365）

## PUT /points-validity?pointsValidDays={days}

- Auth: ADMIN（非 ADMIN 返回 `403 无权限，仅管理员可改营销配置`）
- 校验: 天数必须为正，否则 `400 有效期天数必须为正`
- 成功: 返回更新后的 `{ pointsValidDays }`；只影响**之后**的入账，已落桶的 expire_at 不变

## GET /draw-count-validity

- Auth: 需要登录
- 成功: `{ drawCountValidDays }`（draw_count_config 单行，种子 30）

## PUT /draw-count-validity?drawCountValidDays={days}

- Auth: ADMIN；校验与返回同上

## GET /rebates

- Auth: 需要登录
- 成功: `[{ id, actionType, rebateType, rebateName, rebateDesc, rebateValue, configId, state, createTime, updateTime }]`
  （create_time ASC）

## POST /rebates

- Auth: ADMIN
- Body: `{ actionType, rebateType, rebateName, rebateDesc, rebateValue, configId, state }`
  - `actionType`: `sign` / `pay`；`rebateType`: `POINTS` / `DRAW_COUNT`（非法值 → `400 不支持的返利类型`）
  - `rebateValue` ≥ 0；`state`: `open` / `close`
- 幂等键 = uk `(actionType, rebateType)`：同键重提走**更新**（name/desc/value/configId/state 整行覆盖），
  返回 upsert 后的一行

流程图：Controller（ADMIN 校验）→ `IConfigService`（业务校验）→ `IConfigRepository` →
ConfigMapper.xml（单行 UPDATE / `ON DUPLICATE KEY UPDATE`）。

## 谁在读这些配置

- 有效期：points / drawcount 两域入账算 `expire_at` 时只读注入 `IConfigRepository`
- 返利规则：`IPointsAccountService.creditForRebate`（POINTS 行）、
  `IDrawCountAccountService.creditForRebate`（DRAW_COUNT 行）只查 `state=open` 的规则值
