# 03: 帖子与草稿视图簇改字符串 ID

**What to build:** 帖子详情（点赞 / 发根评论 / 回复子回复 / 展开楼中楼 / 删评论）、草稿编辑与草稿箱、主页信息流卡片这几处，ID 全部按不透明字符串承载。这是「点开帖子被弹回首页」「评论按 ID 比对串台」的直接修复点。

**Blocked by:** 01, 02

**Status:** ready-for-agent

票 01 之后线契约已是 `string`，票 02 之后 `/post/:id` 已不限数字。本票只改视图层，**不要**再动 `types/api.ts`、`api/*`、`router`、`stores`。

## 1. `front/src/views/post/PostDetailView.vue`（本簇主战场）

- `const postId = String(route.params.id ?? '')`（禁止 `Number()` / `parseInt`；取到即字符串直接交给 api 层）。
- `threadStates: reactive<Record<string, ThreadState>>({})`（键 = 根评论 id）。
- `replyTarget: ref<{ id: string; nickname: string; rootId: string | null } | null>(null)`。
- `commentLikeBusyIds = reactive(new Set<string>())`（按评论 id 防抖）。
- 函数形参：`loadReplies(rootId: string)`、`hasMoreReplies(rootId: string)`、`refreshThread(rootId: string)`、`startReply(id: string, nickname: string, rootId: string | null)`、`onDeleteReply(r: CommentReply, rootId: string)`、`isAuthorComment(userId: string)`、`openAuthor(userId: string)`。
- 删评论后按 `commentId` 从列表过滤、楼中楼里按 `parentId` / `commentId` 比对：字符串严格相等即可，比对关系不变。
- 空态兜底保持（UUID 解析失败 = 后端 404 = 现有「内容不存在 / 加载失败」态；旧数字形链接也落这里，不写数字兼容分支）。

## 2. `front/src/views/post/PostPublishView.vue`

- `const draftId = ref<string | null>(typeof route.query.draft === 'string' && route.query.draft ? route.query.draft : null)`（原样承载，不做 `Number()`）。
- 其余 `draftId.value === null` 判断、`updatePostDraft(draftId.value, …)`、`getPost(draftId.value)`、`submitPostDraft(draftId.value)` 不变（类型自动跟随）。

## 3. `front/src/views/post/PostDraftBoxView.vue`

- `actingId = ref<string | null>(null)`（草稿操作中的 id）。
- `withLock(postId: string, action: () => Promise<void>)`。
- `?draft=${p.postId}`、`/post/${p.postId}` 模板串不用改（字符串插值）。

## 4. 只审计、预计零改动的文件

`front/src/views/HomeView.vue`（`:key="p.postId"`）、`front/src/components/PostCard.vue`（`/post/${post.postId}`、`:seed="post.postId"`，`AppImage.seed` 已接受 `string | number`）、`front/src/components/ProfileCommentRow.vue`（评论行按 id 取数）。若发现有 `number` 注解或数值解析才改；否则保持原文件不动，避免无意义 diff。

## 不碰

`PostDetailView` 的版式与文案、点赞/评论交互顺序；`front/src/views/AuthorProfileView.vue`、`ProfileView.vue`、`SearchView.vue`（票 04）；商品/订单/会话/营销各视图（票 05–07）。

## 验收（静态检索自查，**不跑构建 / 类型检查 / 测试**）

- [ ] `front/src/views/post/` 与 `front/src/components/PostCard.vue`、`ProfileCommentRow.vue` 中 `Number(` / `parseInt(` 命中 0（本簇只允许 `likeCount` 之类数值运算，不允许对 id 解析）。
- [ ] 上述文件中 `Id\s*[:?]\s*number`、`Record<number`、`Set<number>` 命中 0。
- [ ] 手动走查 4 条路径的代码可读性：帖子详情加载 → 点赞 → 发根评论 → 展开楼中楼 → 回复子回复 → 删评论；草稿箱 → 编辑草稿 → 提交。
- [ ] `git diff --stat` 只包含本票文件。
