# 04: 个人主页 / 搜索 / 设置视图簇改字符串 ID

**What to build:** 作者主页（按路由 userId 取资料与动态、私信入口）、「我的」页（按 auth userId 取资料 / 评论 / 商品）、搜索结果卡片、浏览历史与收藏、设置两页，ID 全部按字符串透传。

**Blocked by:** 01, 02

**Status:** resolved（作者主页 `userId` 取字符串，五条取数链路与私信入口实参一致；`auth.userId` 随线契约转 `string | null`；搜索 / 历史 / 收藏 / 设置审计后零改动）

票 01 之后 `UserSummary.userId` / `PublicUserProfile.userId` / 三个 `listUser*` 形参都是 `string`，`auth.userId` 也自动变成 `string | null`；票 02 之后 `/user/:userId` 不限数字。本票只改视图层。

## 1. `front/src/views/AuthorProfileView.vue`（本簇主战场）

- `const userId = computed(() => String(route.params.userId ?? ''))`（禁止 `Number()`）。
- `isSelf` 的 `auth.userId === userId.value` 两侧同为字符串，比对关系不变。
- `getPublicProfile`、`listUserPosts`、`listUserLikedPosts`、`listUserComments`、`listUserProducts`、`createDirectRoom(userId.value)`、`router.push(\`/chat/${room.roomId}\`)` 全部跟随类型，无需强转。
- `watch(userId, …)` 保持（字符串变化同样触发）。

## 2. `front/src/views/ProfileView.vue`

- `auth.userId` 已是 `string | null`；`if (!auth.isLoggedIn || !auth.userId) return` 对空串/`null` 同样成立，逻辑不变。
- `listUserComments(auth.userId, …)`、`getPublicProfile(auth.userId)` 直接透传。
- 模板里 `/post/${p.postId}`、`/product/${p.productId}`、`:key`、`:seed` 都是字符串插值，无需改。

## 3. `front/src/views/SearchView.vue`

用户结果卡片当前不可点（无跳转），帖子 / 商品结果走 `PostCard` / `ProductCard`（票 03/05 已覆盖），本票预计零改动，仅审计 `:key="u.userId"` 等绑定。

## 4. 其余审计文件（预计零改动）

`front/src/views/me/ViewHistoryView.vue`、`front/src/views/me/MyFavoritesView.vue`（`:key` 与卡片透传）、`front/src/views/settings/ProfileEditView.vue`、`front/src/views/settings/PrivacySettingsView.vue`（`auth.userId` 守卫与 `getPublicProfile(auth.userId)`）。发现 `number` 注解或数值解析才改。

## 不碰

`front/src/views/post/*`（票 03）、`views/idle/*` 与 `views/me/MyOrdersView.vue`（票 05）、`views/chat/*` 与 `views/msg/*`（票 06）、`views/marketing/*`（票 07）。不改版式、文案、交互。

## 验收（静态检索自查，**不跑构建 / 类型检查 / 测试**）

- [x] 本票文件中 `Number(` / `parseInt(` 命中 0。
- [x] 本票文件中 `Id\s*[:?]\s*number` 命中 0。
- [x] 作者主页：`/user/<uuid>` 取到字符串 → 资料 / 帖子 / 评论 / 闲置 / 私信五条调用链的实参类型一致（逐行读一遍）。
- [x] `git diff --stat` 只包含本票文件。

## Comments

- 仅 `AuthorProfileView.vue` 一行改动；其余六个文件审计后零改动。
- SearchView 的用户结果卡片改造前就不可点，按「不改交互」未新增跳转（spec Out of Scope：本轮之外的新功能）。
