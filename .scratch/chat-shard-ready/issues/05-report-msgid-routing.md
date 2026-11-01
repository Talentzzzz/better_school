# 05: 举报 MESSAGE 经举报人 timeline 定位消息

**What to build:** 举报 MESSAGE 时不再按 `uk_msg_id` 单键直查消息（分库后该查询无法路由），改为经举报人自己的 timeline 行取 `(msg_id → room_id, seq)` 再按会话键取正文。完成后：举报链路对分片天然友好，分库预案的一处读路径提前成立。

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] 举报 MESSAGE 的消息定位改为经举报人 timeline 行取 `(room_id, seq)` 路由
- [ ] 举报人收件箱中无该行时的降级语义明确（如经会话成员关系定位，低频广播可接受）
- [ ] 举报链路集成测试回归：定位、审核撤回联动不受影响
