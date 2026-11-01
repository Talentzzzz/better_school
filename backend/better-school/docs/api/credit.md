# 信用分模块接口

账号可信度分值与封禁的查询 / 管理面。`my` 系列需登录；`admin` 系列仅 ADMIN（controller 内角色校验，非 ADMIN 403）。表结构见 `docs/db/user.md` 的 `user_credit_detail`。

## GET /my
我的信用信息。返回 `data = {creditScore, status}`（`status`：NORMAL / BANNED；封禁账号不可发布或接单）。

## GET /my/logs
我的信用流水（分页，`?page=1&pageSize=10`，pageSize ≤ 50）。每条含 `id / change（正加分负扣分）/ reasonType / reasonDesc / refId / createdAt`。

## POST /admin/adjust
管理员调分。Body: `targetUserId`, `delta`（正负整数）, `reason`。落流水。

## POST /admin/ban
管理员封禁。Body: `targetUserId`, `reason`。封禁后目标用户不可发布内容 / 接单。

## POST /admin/unban
管理员解封。Body: `targetUserId`。

## GET /admin/logs?userId
指定用户的信用流水（分页参数同 `/my/logs`），供管理面查任意账号。
