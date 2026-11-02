# 04 · 复用库存的延迟消息与 outbox 需要改什么

Type: research
Status: open
Blocked by: 01

## Question

若走增量累加器，唤醒机制要复用库存侧已建好的 `outbox_message`（含 `delay_level`）+ MQ 延迟等级 1 + 兜底扫描。要查实并落档：

1. 现有 `outbox_message` 的 `destination` 映射、消费组命名、扫描重发是否按 `delay_level` 走；计数直接复用同一个 outbox 表与兜底 job 会不会把两类消息的失败率混在一起。
2. 生产端「每笔点赞一条消息」vs「`SET NX PX` 节流窗口」的消息量差；消费端 `GETSET` 取走 + 失败 `INCRBY` 回填之后，节流窗口会不会漏掉窗口尾部的增量（库存侧的解法是「合并侧自我续命」，计数侧要不要一个常驻补刷）。
3. 兜底扫描怎么发现「Redis 里还有 Δ 没被取走」——按 key 模式 `SCAN`、还是维护一个待刷 Set；`SCAN` 在百万 key 下的单轮成本。
4. RocketMQ 延迟等级 1 的实际抖动量级（ADR-0011 记的是 ≈1.0–1.1s），对「点赞后多久看到数字变」的用户可感影响。

## Answer

（待定）
