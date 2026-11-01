# 票 05 · 重写合并集成测试

Type: task
Status: open

Blocked by: 04

## Question

现有两个集成测试断言的正是**被删掉的语义**，必须重写而不是修补：

- `InventoryMergeEngineIntegrationTest`（断「最老一格」「续命」「翻转行数判重」）
- `InventoryMergeTriggerIntegrationTest`（断「1 秒延迟消息 + 窗口内只发一条」）
- `InventoryMergeTestSupport` / `SwitchableInventoryBucketPort` 可能也要跟着调

要覆盖的新语义：

| 用例 | 断言 |
| --- | --- |
| 一条消息合两格 | `cellTime = s` 落库，消息到达后 `s`、`s+1` 两格都 `state=1`，台账 `waiting/issued` 正确 |
| 第二格空转 | 先合 `s+1` 再消费同 `cellTime` 的消息 → 两次 `mergeCell` 返回 0，台账不变 |
| 不续命 | 消费完不清空时**没有**新 outbox 行产生（查 `outbox_message` 行数不变） |
| 顺序保证 | `s+1` 有 `type=2`、`s` 有 `type=1` 时，同一消息内先合 `s` 再合 `s+1`，`waiting` 守卫通过 |
| 兜底仍工作 | 消息整条丢弃时，`backstopOnce()` 能把两格合掉 |
| 载荷缺 `cellTime` | 消费者丢弃、不落任何数据，`log.error` |
| 定时投递 | `outbox_message.delay_ms = 2000`、`delay_level = 0`；扫描重发时该行直接普通发送 |
| 实际投递时延 | 记录「发出 → 消费」的耗时，断言落在 **2.0~3.2s**（票 01 的源码结论：时间轮只会晚 0~1s；这是边界 2 的真机确认点） |

跑法沿用现有 profile（`bs_marketing_test` + docker 中间件）。**全绿才算这一票完成。**

## Answer

（待填）
