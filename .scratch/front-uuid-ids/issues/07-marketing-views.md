# 07: 营销视图簇改字符串 ID（奖品 / 记录 / 购买单号）

**What to build:** 营销活动页（奖池目录、抽奖结果、用积分换次数）与「我的奖品」页（列表、确认收货）里，奖品 id / 抽奖记录 id / 购买单号一律按字符串承载；取色不能再对 id 做取模运算。

**Blocked by:** 01, 02

**Status:** ready-for-agent

票 01 之后 `LotteryPrize.prizeId`、`DrawResult.recordId` / `prizeId`、`UserPrize.id` / `prizeId`、`confirmReceived(id: string)`、`exchangeDrawCount` 返回的 `redeemOrderId` 都是 `string`。`tier`、`pointsAmount`、`remaining`、`total`、`drawCount`、积分余额仍是 `number`。

## 1. `front/src/api/marketing.ts`（新增一个编号取色辅助）

`PRIZE_TINTS[p.prizeId % PRIZE_TINTS.length]` 在 UUID 上会得到 `NaN`。在 `PRIZE_TINTS` 旁新增确定性哈希取色，**保留** `PRIZE_TINTS` 与 `tierText` 的现有导出（`tierText` 若要跟着票 01 一起改就保持原样）：

```ts
/** 按奖品 ID 取色：同一奖品在目录、我的奖品、抽奖结果里恒定同色（ID 是 UUID，不能用取模） */
export function prizeTint(prizeId: string): string {
  let h = 0
  for (let i = 0; i < prizeId.length; i++) h = (h * 31 + prizeId.charCodeAt(i)) | 0
  return PRIZE_TINTS[Math.abs(h) % PRIZE_TINTS.length]
}
```

## 2. `front/src/views/marketing/MarketingView.vue`

- `const tintAt = (p: LotteryPrize) => prizeTint(p.prizeId)`（替换 `PRIZE_TINTS[p.prizeId % …]`，原注释「按 id 取而非目录序号」的语义保持不变）。
- `prizes.value?.findIndex((p) => p.prizeId === drawResult.value?.prizeId)` 字符串相等，保持。
- `draw(crypto.randomUUID())` / `exchangeDrawCount(crypto.randomUUID())` 的 token 本来就是字符串，不动；`drawCountValidDays`、`drawCount` 等数值不动。

## 3. `front/src/views/marketing/MyPrizesView.vue`

- `const receiving = ref<string | null>(null)`（确认收货中的奖品 id）。
- `receiving.value = p.id`、`confirmReceived(p.id)`、`receiving === p.id` 全部字符串，关系不变。
- `const tintOf = (p: UserPrize) => prizeTint(p.prizeId)`（替换取模）。
- `:key="p.id"`、`tierText(p.tier)` 不动。

## 不碰

`front/src/types/*`、`front/src/api/` 里除本票新增的 `prizeTint` 之外的任何内容（票 01）、`router` / `stores`（票 02）、其它视图簇（票 03–06）。不改版式、文案、交互；购买单号只当标识展示，不做数值计算。

## 验收（静态检索自查，**不跑构建 / 类型检查 / 测试**）

- [ ] `front/src/views/marketing/` 中 `Id %` / `Id\s*[:?]\s*number` / `Number(` / `parseInt(` 命中 0。
- [ ] `PRIZE_TINTS[… % …]` 的直接取模只剩 `prizeTint` 内部一处。
- [ ] 逐行确认：目录渲染、抽奖结果定位、我的奖品列表、确认收货提交四条链路实参类型一致（`string`），而 `tier` / 次数 / 积分余额仍是 `number`。
- [ ] `git diff --stat` 只包含本票文件。
