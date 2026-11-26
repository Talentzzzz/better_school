# 04: 积分/抽奖次数 — 按显式金额返利入账 + 通知文案

**What to build:** 积分域与抽奖次数域各增加「按显式金额入账的返利方法」，供 credit 消费者使用（消费端不再读规则）；保留现有按 actionType 读配置的方法给其他行为。积分变动通知把 `REBATE` 文案改为「发帖返利」。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

## 契约

### `IPointsAccountService` 新增

```java
/**
 * 按显式金额入账行为返利（credit 消费者用）：金额 <= 0 直接返回 false 不落桶；
 * 幂等键 (userId, REBATE, refId)；撞键只记日志，不重复入账、不重复发变动事件。
 */
boolean creditRebate(String userId, int amount, String refId);
```

实现：参数校验（userId/refId 非空，amount >= 0）→ `amount <= 0` 返回 false → `pointsRepository.insertBucketWithChanged(userId, amount, PointsReasonType.REBATE, refId)`；返回 true（撞键也返回 true，表示「已到账」）。保留 `creditForRebate(actionType, userId, refId)` 原样。

### `IDrawCountAccountService` 新增

```java
boolean creditRebate(String userId, int amount, String refId);
```

实现同构：`amount <= 0` 返回 false，否则走既有 `credit(userId, amount, DrawCountReasonType.REBATE, refId)`（撞 `uk_user_reason_ref` 幂等）。保留 `creditForRebate(actionType, userId, refId)`。

### `MarketingNoticeCase.notifyPointsChanged`

switch 增加 `case "REBATE" -> "发帖返利";`（放在 `LOTTERY_WIN` 之后即可），保证通知中心文案不是「积分变动」。

## 验收

- [ ] 两个新方法与既有方法签名并存，互不覆盖
- [ ] 金额 0/负数不落桶、不发通知
- [ ] 重复调用同一 `(userId, refId)` 只有一条桶行、一条变动通知（既有唯一键吸收）
- [ ] 通知文案含「发帖返利」
