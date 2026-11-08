# 用户模块接口

所有接口 base `/api/user`。send-code / register / login 与公开资料 GET 免登录（Security 白名单），编辑资料 / 隐私设置需登录（JWT Bearer）。

## POST /send-code · POST /register · POST /login

现有登录注册链路（略）。

- `login` 返回 `UserInfo`（phone/role/status/creditScore/lastLoginAt），**不含签名/性别**——「我的」页资料区的签名/性别改走 `GET /{userId}/profile`

## GET /{userId}/profile

公开用户资料。作者主页与「我的」页资料区共用（本人看自己传自己的 `userId`）。

- Auth: 免登录
- 成功: `PublicUserProfileResponseDTO`
  - `userId`、`nickname`、`avatar`
  - `signature`（可空）、`gender`（`MALE`/`FEMALE`，可空＝不展示）
  - `receivedLikeCount`：**获赞**口径＝该用户 `status='NORMAL'` 帖子的 `like_count` 之和（**不含**评论获赞、**不含**草稿帖）
  - `showLiked` / `showComments`：两个隐私开关当前值，**始终返回**（前端据此渲染「该用户已隐藏此内容」空态）
- **不返回 `phone`**（PII）：访客资料行只显示 昵称 + 性别 + 签名，无「账号」行
- 用户不存在 → 404

## PUT /profile

编辑当前用户资料（「设置 → 编辑资料」）。**整体覆盖可编辑的两字段**（编辑页每次提交both字段；`null`/空串＝清空/不展示）。

- Auth: 需要登录
- Body: `signature`（≤40 字，空串/null＝清空）、`gender`（`MALE`/`FEMALE`/null/空串＝不展示）
- 成功: 返回更新后的 `PublicUserProfileResponseDTO`
- 校验: 签名超 40 字 → 参数错误；`gender` 非 MALE/FEMALE/null/空串 → 参数错误

## PUT /privacy

改两个全局隐私开关（「设置 → 隐私设置」）。

- Auth: 需要登录
- Body（**部分更新**，缺省即不改）: `showLiked?` / `showComments?`（boolean）
- 成功: 返回 `{ showLiked, showComments }`
- **生效口径（仅他视）**：`showLiked=0` 时，**他人**访问 `GET /api/community/users/{userId}/liked` 返回空列表（前端渲染「该用户已隐藏此内容」）；`showComments=0` 同理影响 `…/comments`。**主页主人自己看自己不受影响**（两个接口在请求者＝主人时忽略开关）
