Status: resolved
Type: task
Blocked by: 01, 02, 03, 04, 05, 06

# 07: 集成测试汇总 + 全链路冒烟

**What to build:** 按 spec 的 Testing Decisions 汇总集成测试（Testcontainers，trigger 层 seam，消费者直接驱动），补齐各票未覆盖的交叉场景（初筛命中→改文案重发成功；支付→consumer 通知→裁决→可见/退款 的完整编排；outbox 定时兜底路径）。冒烟：真实 docker compose（含 MinIO、RocketMQ、xxl-job）走通 上传 → 创建 → 支付 → 管理员裁决通过/拒绝 两条链路，脚本化断言（参照 `.scratch/comment-nesting/browser-e2e/` 先例）。

**Status:** resolved

- [x] 集成测试全绿，覆盖 spec 列出的场景清单（79/79；新增 `ErrandFullChainIntegrationTest` 补齐改文案重发、支付→通知→裁决双链编排、outbox 扫描兜底）
- [x] 真实 compose 冒烟：通过链路（可见 + 通知）与拒绝链路（退款 + 理由）双通（`smoke-ticket07.py` 17/17）
- [x] 冒烟脚本入库本 feature 目录
