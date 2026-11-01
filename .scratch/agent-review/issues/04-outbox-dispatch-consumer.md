# 04: 审核 outbox 生产 + 投递消费者 + 降级骨架

**What to build:** 待审内容到 agent 的投递管道与全部降级路径。五类内容（帖子/评论/商品/求购/跑腿）发布事务同写审核 outbox（新 topic bs-agent-review，消息体 target_type/target_id/review_round）；Java 消费者组装作者档案（信用分、账号状态、账号年龄、被拒次数与理由分布、近 1h/24h 发布数、近 30 天被拒率、各类型累计发布数、被举报数）POST 到 Python 服务 adapter（接口先行，Python 未就绪即走降级，测试用 fake adapter）。降级矩阵：MQ 重试 3 次 → 转人工 + 提醒审核员；健康探活不健康 → 跳过重试直接转人工；yml 总开关关闭 → 不发审核 topic、行为与现状完全一致。评论旧 outbox 链路调整：提醒时机从"发布即提醒"移至"转人工时刻"。

**Blocked by:** 03

**Status:** ready-for-agent

- [x] 开关关闭 → 发布链路与现状完全一致
- [x] 开关开启 → 五类内容发布后消息进 outbox，消费者组装档案并投递
- [x] 作者档案字段齐全且数值正确（各 count 口径有集成测试）
- [x] Python 不可达 → 重试 3 次 → 转人工 + 提醒审核员
- [x] 探活不健康 → 不空转重试，直接转人工 + 提醒
- [x] 评论发布不再"发布即提醒"，转人工时刻才提醒
- [x] 集成测试覆盖全降级链路（fake adapter）
