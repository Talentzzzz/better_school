# 07: 前端 — 发帖/草稿提交返回结构化返利结果 + toast 组装

**What to build:** `createPost` 与 `submitPostDraft` 返回结构化结果；`PostPublishView` 按 `rebateState` 组装四种 toast。老客户端忽略新增字段仍可发布（本票不清理全局 number→string）。

**Blocked by:** 01（契约）, 06（HTTP 形态落地）

**Status:** resolved

## 契约

`front/src/types/api.ts` 或 `front/src/api/community.ts` 新增：

```ts
export type RebateState = 'CLAIMED' | 'DENIED' | 'NO_REBATE' | 'PENDING'

export interface PostPublishResult {
  postId: string
  rebateState: RebateState
  pointsRebate: number
  drawCountRebate: number
}
```

`front/src/api/community.ts`：
- `createPost(body): Promise<PostPublishResult>` → `httpPost<PostPublishResult>('/community/posts', body)`
- `submitPostDraft(postId): Promise<PostPublishResult>` → `httpPost<PostPublishResult>(...)`
- `savePostDraft` 仍返回 `number`（后端未改），`updatePostDraft` / `deletePost` 等不变。

## `PostPublishView.vue`

`onSubmit` 两段路径都拿结果并展示：
- 直接发布：`const result = await createPost(formBody())`
- 草稿发布：`const result = await submitPostDraft(draftId.value)`（`updatePostDraft` 保持先调）

新增本地函数：

```ts
function rebateText(result: PostPublishResult): string {
  switch (result.rebateState) {
    case 'CLAIMED': {
      const parts: string[] = []
      if (result.pointsRebate > 0) parts.push(`${result.pointsRebate} 积分`)
      if (result.drawCountRebate > 0) parts.push(`${result.drawCountRebate} 次抽奖`)
      return parts.length > 0 ? `获得 ${parts.join(' + ')}` : '发布成功'
    }
    case 'DENIED':
      return '今日发帖返利已领过'
    case 'PENDING':
      return '返利发放中'
    default:
      return '发布成功'
  }
}
```

toast 用 `toast(rebateText(result))` 替换两处 `toast('发布成功')`，其余跳转逻辑不变。

## 验收

- [x] CLAIMED 展示「获得 X 积分 + Y 次抽奖」（只展示 >0 的项）
- [x] DENIED 展示「今日发帖返利已领过」
- [x] PENDING 展示「返利发放中」
- [x] NO_REBATE 展示「发布成功」
- [x] 直接发布与草稿提交两条路径都走同一套文案
- [x] TypeScript 类型自洽（`rebateState` 联合类型，无 `any`）
- [x] 未触碰未涉及的接口（save/update/delete 等）返回类型

## Comments

**2026-11-26** — 已实现并静态自检（按要求未跑 npm install/build）—

改动：

- `front/src/types/api.ts`：新增 `RebateState` 联合类型与 `PostPublishResult`（`postId` 为 `string`，对齐 `PostPublishResponseDTO`），放在 community 区 `PostDetail` 之后。
- `front/src/api/community.ts`：`createPost` 由 `httpPost<number>` 改为 `httpPost<PostPublishResult>`；`submitPostDraft` 由 `httpPost<null>` 改为 `httpPost<PostPublishResult>`；`savePostDraft` 仍返回 `number`，`updatePostDraft` / `deletePost` 未动。
- `front/src/views/post/PostPublishView.vue`：新增本地 `rebateText(result)`，两段 `onSubmit` 路径各自 `const result = await ...` 后 `toast(rebateText(result))`；`updatePostDraft` 仍在 `submitPostDraft` 之前；`leaveQuietly` / `router.replace` 跳转不变。

静态自检：全 `front/src` grep `createPost` / `submitPostDraft`，调用方仅 `PostPublishView.vue`（且本票已同步），无旧返回类型残留；`PostPublishResult` 在 `community.ts` 与视图中均已 `import type`；无 `any`；diff 仅上述三个文件。未触碰后端与其他视图。
