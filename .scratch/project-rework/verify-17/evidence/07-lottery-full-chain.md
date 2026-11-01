# 冒烟项 7：抽奖全链路（次数 → 责任链 → 库存预扣 → 合并任务 → 发货）

**判定：PASS**（19/19 断言；0 FAIL）

- 脚本：[`smoke_06_07_09_points_lottery.py`](../smoke_06_07_09_points_lottery.py)（号段 `137000174xx`）
- 运行记录：[`06-07-09-run.txt`](06-07-09-run.txt)；机读汇总：[`06-07-09-summary.json`](06-07-09-summary.json)
- 夹具口径照 `RaffleApiTestBase`（活动 / 策略 / 奖池 / 库存 无种子，脚本自建）

## 7.1 表预热（xxl 决策侧）

| # | 断言 | 证据 |
| --- | --- | --- |
| 7.1a | 手动触发 xxl `raffleCacheWarmup`（参数 = activityId）→ Redis `bs:raffle:warmup:*` 表就位 | [`07a-warmup-jobs.json`](07a-warmup-jobs.json) / [`07l-physical-warmup.json`](07l-physical-warmup.json) |
| 7.1b | 手动触发 xxl `inventoryBucketWarmup`（空参 = 全量）→ 三段桶 key 均在（虚拟 2 + 实物配额 1） | 同上 |
| 7.1c | `GET /api/lottery/activity` 返回进行中的活动（`status=ONGOING`） | [`07b-activity.json`](07b-activity.json) |
| 7.1d | 前置：次数桶 3 次可抽（`GET /api/lottery/draw-chances` → `available=3`） | [`07b2-draw-chances.json`](07b2-draw-chances.json) |

> 预热任务经 **xxl-job-admin（9090）**手动触发（脚本登录 admin 调 `jobtrigger`），不是绕过调度器直接调 bean。

## 7.2 虚拟档（60 积分）

| # | 断言 | 证据 |
| --- | --- | --- |
| 7.2a | `POST /api/lottery/draw` 成功，返回 `prizeType=VIRTUAL / prizeId / tier / name / pointsAmount` | [`07d-draw-virtual-response.json`](07d-draw-virtual-response.json) / [`07c-inventory-before.json`](07c-inventory-before.json) |
| 7.2b | 次数桶 `remain 3→2`，且 `draw_count_detail` 有 `(bucket_id, ref_id=recordId)` 一行 | [`07e-draw-count-deducted.json`](07e-draw-count-deducted.json) |
| 7.2c | `draw_record` 落库（命中责任链选出的 award）+ `draw_record_detail(type=DRAW)` 一行 | [`07f-draw-record.json`](07f-draw-record.json) |
| 7.2d | 虚拟库存**已预扣**：`virtual_inventory_detail` 有 `quantity=1` 明细（落库态 `state=0`；合并已跑则以 `issued_qty=1` 等价证明） | [`07g-virtual-prededuct.json`](07g-virtual-prededuct.json) |
| 7.2e | **xxl `inventoryMerge`（秒级）**把预扣翻进台账：`virtual_inventory.issued_qty 0→1`、明细 `state=1` | [`07h-virtual-merged.json`](07h-virtual-merged.json) |
| 7.2f | 发货状态机推进（**真实 MQ 消费**）：`draw_record.state=已完成`，明细 `DRAW+EXCHANGE+COMPLETE` | [`07i-virtual-shipping.json`](07i-virtual-shipping.json) |
| 7.2g | 真实 MQ 消费 `bs-lottery-drawn` 的积分侧：`points_bucket` 新增 `LOTTERY_WIN` 行（`amount=60`、`ref_id=recordId`） | [`07j-points-lottery-win.json`](07j-points-lottery-win.json) |
| 7.2h | 中奖事件经 outbox **真实投递**：`bs-lottery-drawn` / `bs-points-changed` 两行 `state=1`（已发送） | [`07k-outbox-drawn.json`](07k-outbox-drawn.json) |

## 7.3 实物档（校园帆布包）

| # | 断言 | 证据 |
| --- | --- | --- |
| 7.3a | `POST /api/lottery/draw` 命中实物档（`prizeType=PHYSICAL`） | [`07l-draw-physical-response.json`](07l-draw-physical-response.json) |
| 7.3b | 实物配额预扣：`physical_marketing_quota_detail` 有 `quantity=1` 明细（落库态 `state=0`，或等价 `waiting_qty=1`） | [`07m-physical-prededuct.json`](07m-physical-prededuct.json) |
| 7.3c | `inventoryMerge` 把预扣翻进台账：`waiting_qty +1`、明细 `state=1`，且 **`surplus + waiting + issued` 守恒 = 总量 10** | [`07n-physical-merged.json`](07n-physical-merged.json) |
| 7.3d | 实物发货：`draw_record.state=已发货`、明细含 `SHIP`、**兑换确认 `waiting→issued`**（`waiting 1→0 / issued 0→1`） | [`07o-physical-shipping.json`](07o-physical-shipping.json) |
| 7.3e | `POST /api/lottery/my-prizes/{id}/receive`：`已发货→已完成`，明细 `DRAW/SHIP/COMPLETE` | [`07p-confirm-receive-response.json`](07p-confirm-receive-response.json) |
| 7.3f | 「我的奖品」`COMPLETED` 列表含本次实物记录 | [`07q-my-prizes-completed.json`](07q-my-prizes-completed.json) |

## 两处断言口径修正（脚本侧，非业务缺陷）

1. **7.2c 的期望状态**：初版要求 `state=待兑换`。票 14 的口径是**虚拟档落库即推进**
   （`EXCHANGE`+`COMPLETE` 一步到已完成），而真实 MQ 消费常在 1s 内跑完——脚本查到时已是「已完成」。
   改为接受 `待兑换 | 已完成` 两态，终态由 7.2f 断言。
2. **7.3c 的配额守恒式**：初版要求 `surplus_qty` 恒等于初始总量。实际语义是
   `surplus_qty` = **剩余可发份数**（`schema.sql` 列注释），预扣合并进 `waiting_qty` 后它同步 −1，
   守恒式应为 `surplus + waiting + issued == 总量`（实测 9+1+0=10 ✓）。

## 结论

抽奖主链路五段全部在全栈上走通，且每一段都留下了**库内事实**：
次数桶扣减 → `draw_record`/`draw_record_detail` → 库存预扣（`state=0`）→ `inventoryMerge` 翻台账 → 发货状态机 → outbox/MQ → 积分入账与通知。
虚拟档与实物档两条差异路径（直达已完成 / 先确认再发货）都验证到位。未发现业务缺陷。
