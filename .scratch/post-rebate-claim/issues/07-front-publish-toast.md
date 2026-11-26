# 07: 前端 — 发帖/草稿提交返回结构化返利结果 + toast 组装

**What to build:** `createPost` 与 `submitPostDraft` 返回结构化结果；`PostPublishView` 按 `rebateState` 组装四种 toast。老客户端忽略新增字段仍可发布（本票不清理全局 number→string）。

**Blocked by:** 01（契约）, 06（HTTP 形态落地）

**Status:** ready-for-agent

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

- [ ] CLAIMED 展示「获得 X 积分 + Y 次抽奖」（只展示 >0 的项）
- [ ] DENIED 展示「今日发帖返利已领过」
- [ ] PENDING 展示「返利发放中」
- [ ] NO_REBATE 展示「发布成功」
- [ ] 直接发布与草稿提交两条路径都走同一套文案
- [ ] TypeScript 类型自洽（`rebateState` 联合类型，无 `any`）
- [ ] 未触碰未涉及的接口（save/update/delete 等）返回类型
