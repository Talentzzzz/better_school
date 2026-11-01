# 冒烟项 9：通知（中奖 / 积分变动）落库

**判定：PASS**（7/7 断言；0 FAIL）

- 脚本：[`smoke_06_07_09_points_lottery.py`](../smoke_06_07_09_points_lottery.py)
- 运行记录：[`06-07-09-run.txt`](06-07-09-run.txt)；机读汇总：[`06-07-09-summary.json`](06-07-09-summary.json)

## 证明链

| # | 断言 | 证据 |
| --- | --- | --- |
| 9.1 | 中奖通知落 `notification`：收件人 = 中奖用户、`type=LOTTERY_WON`、`title/content` 非空、`ref_id` = `recordId`、`dedup_key=MARKETING:DRAWN:<recordId>` | [`09a-notifications.json`](09a-notifications.json) |
| 9.2 | 中奖文案按奖品档呈现：VIRTUAL 档含积分数字（「恭喜您在抽奖中抽中 60 积分，已发放至您的积分账户」） | 同上 |
| 9.3 | 积分变动通知落库：`LOTTERY_WIN` 入账 → `type=POINTS_CHANGED`、`content` 含变动量与当前余额（「抽奖中奖：积分 +60，当前余额 300」） | 同上 |
| 9.4 | **兑换扣分**也落积分变动通知：`REDEEM_DRAW_COUNT`、`content` 含负数与余额（「兑换抽奖次数：积分 -100，当前余额 240」） | 同上 |
| 9.5 | 按收件人隔离：对照用户 `13700017402` 无任何 MARKETING 通知行 | 同上 |
| 9.6 | 字段齐备：`scope=BOTTOM_SYSTEM`、`seq`、`type` / `title` / `content` / `dedup_key` 均非空 | 同上 |
| 9.7 | 读侧接口探测（`GET /api/chat/notifications`）HTTP 200 | [`09b-notification-list-api.json`](09b-notification-list-api.json) |

## 关键点：通知走的是**真实 MQ**，不是同进程调用

开发栈的 RocketMQ broker 在跑（namesrv 9876），营销侧中奖事件先落 `outbox_message`（`destination=bs-lottery-drawn` /
`bs-points-changed`，`state=1` 已发送），由单体消费者转成 `notification` 行——
`outbox` 已发送状态见项 7 的 [`07k-outbox-drawn.json`](07k-outbox-drawn.json)。

## 结论

「中奖 / 积分变动 → 通知落库」这条跨服务异步链路（营销 outbox → broker → 单体消费者 → `notification`）走通，字段齐全并正确隔离收件人。
