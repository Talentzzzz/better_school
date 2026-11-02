# 01 · 计数口径基线：增量累加器 vs Redis 覆盖

Type: grilling
Status: claimed
Blocked by: 无

## Question

互动计数的落库形态选哪一档。三个候选：

- **A（库存全同构）**：每笔点赞落带业务唯一键的明细行 → 1 秒延迟消息 → 时间格合并 → 台账结算 + `state` 翻转。可审、可重放判重，代价是一行明细 + 一套合并事务。
- **B（用户提的覆盖）**：Redis `INCR` 存**绝对值** → 延迟消息把 Redis 的值**覆盖**写回窄表。省掉扫表与 state。
- **C（增量累加器）**：Redis `INCRBY` 存**增量** → 延迟消息 → 消费端 `GETSET key 0` 原子取走 Δ → 窄表 `like_count = like_count + Δ`，失败 `INCRBY` 回填。

要定的是：点赞数 / 评论点赞数 / 浏览量 / 评论总数 这四个字段各走哪一档，以及库存为什么不能跟着简化。

## Answer

（待本轮 grill 收敛后回填）
