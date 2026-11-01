# 冒烟项 6：积分桶入账 + 消费扣桶

**判定：PASS**（7/7 断言；0 FAIL）

- 脚本：[`smoke_06_07_09_points_lottery.py`](../smoke_06_07_09_points_lottery.py)（号段 `137000174xx`）
- 运行记录：[`06-07-09-run.txt`](06-07-09-run.txt)；机读汇总：[`06-07-09-summary.json`](06-07-09-summary.json)

## 证明链

| # | 断言 | 证据 |
| --- | --- | --- |
| 6.1a | `action_rebate_config` 有 `sign/POINTS` 开启规则（`rebate_value=20`）——入账规则的库内事实 | [`06a-points-buckets-seeded.json`](06a-points-buckets-seeded.json) |
| 6.1b | 入账落桶：`points_bucket` 两行、`SUM(amount)=340`；`GET /api/points/balance = 340` | [`06-balance-attempts.json`](06-balance-attempts.json) |
| 6.2a | `POST /api/lottery/exchange/draw-count` 成功（100 积分 → 1 次）；响应 `drawCount` = **兑后可用次数余额**（与 `RedeemDrawCountResponseDTO` 注释一致），脚本按「兑前余额 + 1」断言 | [`06b-exchange-draw-count-response.json`](06b-exchange-draw-count-response.json) |
| 6.2b | **FIFO 跨桶按到期顺序扣**：最早过期的桶（40）扣光、差额 60 落到后过期的桶（300→240） | [`06c-points-deduct-fifo.json`](06c-points-deduct-fifo.json) |
| 6.2c | 消费落两行 `points_detail`：按桶分别记 40 / 60，`reason_type=REDEEM_DRAW_COUNT`，`ref_id` = 兑换单号，合计 100 | 同上 |
| 6.2d | 扣桶后 `GET /api/points/balance = 240`；`draw_count_bucket` 有**本次兑换单号**的 REDEEM 入账一行（`amount=1`、`remain=1`） | [`06d-after-exchange-balance.json`](06d-after-exchange-balance.json) |
| 6.2e | `GET /api/points/transactions` 体现 2 条 `IN` + 2 条 `OUT` | [`06e-points-transactions.json`](06e-points-transactions.json) |

## 口径备注（写清楚，免得下一轮误判）

- **入账侧没有 HTTP/MQ 入口**：`creditForRebate` 只在积分域内部被调用（`docs/api/points.md`），
  所以桶行按 `PointsApiTestBase.insertBucket` 的夹具口径落库，再让**消费侧走真实 HTTP**——
  本项的核心（按桶 FIFO 扣减 + 流水 + 余额）全部由真实接口驱动并断言。
- **响应里的 `drawCount` 是余额不是增量**：票 13 定的契约（`RedeemDrawCountResponseDTO` javadoc 明写
  「兑完之后的可用次数余额」）。脚本初版按增量断言，重跑时被历史次数顶红——是**脚本断言错**，
  已改成「兑前余额 + 1」，不是业务缺陷。

## 结论

桶模型（`points_bucket` 入账 / `points_detail` 流水 / 按到期顺序跨桶扣减 / 余额与流水一致）在全栈上成立。
