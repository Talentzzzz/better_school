# 09 task：spec 定稿（effort 收口）

Type: task
Status: resolved
Blocked by: 01, 02, 03, 04, 05, 06, 07, 08

## Question

把票 01–08 的决策汇总成 `.scratch/errand-lock-review-hub/spec.md`——**本 effort 的终点**。不是新决策，是收口：编号更大的票为准（反转按后票落稿），写清：

1. **范围**：本 spec 覆盖什么、不覆盖什么（Out of scope 与 map 一致）。
2. **跑腿订单状态机**：定稿状态图 + 完整迁移矩阵（票 01）+ 枚举/字段变更 + 存量迁移脚本编号。
3. **锁单超时**：接超时中心的完整设计（票 02）+ 参数（5 分钟）。
4. **防占单**：规则 + 参数默认值 + 数据落点（票 03）。
5. **统一审核中台**：数据模型（票 06）+ 派单方案（票 05）+ 工程形态与部署（票 08）+ 原型结论（票 07）。
6. **术语**：`CONTEXT.md` 新增/修订词条清单（跑腿状态机 + 审核中台）。
7. **ADR 判定**：哪些决策够格落 ADR（候选：跑腿状态机扩展对 ADR-0003 的修订；审核中台「推信号 + 拉工单」派单模型）。
8. **对接影响**：对 `.scratch/timeout-center/spec.md`、`backend/docs/architecture/timeout-center.md` 的回填点（锁单超时 + 审核租约两个新增接入方）。
9. **可观测与运维**：本期做的与开放项分开标注。
10. **Out of scope**：从 map 抄下，逐条定稿。

**产出**：`spec.md` 定稿（Status: accepted）。**本票关闭 = 地图抵达终点**。

## Answer

**resolved（2026-10-14）。** 收口完成：`.scratch/errand-lock-review-hub/spec.md` 已定稿（`Status: accepted`），票 01–08 的决策全部按「编号更大者为准」落稿。**地图抵达终点。**

### 落稿要点（十问逐条）

1. **范围**：覆盖两块（跑腿状态机扩展 + 统一审核中台）＋二者共用的超时接入；不覆盖实现本身、举报申诉、自建账号体系、超时中心旧链路下线、跑腿执行时限接入；**多人单不适用**（在线实现已下线，不继承其状态机）。
2. **跑腿状态机（票 01）**：13 值枚举（新增 `DRAFT`/`LOCKED`/`DELETED`，`TAKEN` **改名 `CONFIRMED`**）＋完整迁移矩阵（含锁单期交叉裁决：发布方可取消、不进仲裁、执行超时白名单加 `LOCKED`）＋`lock_seq` 列、无时间列；**无存量 ⇒ 零迁移脚本**（改 `schema.sql` + 重建库；若恢复编号从 028 续）。修事件撞名（旧 `TAKEN`→`LOCKED`、旧 `CONFIRMED`→`COMPLETED`）。
3. **锁单超时（票 02）**：taskType `ERRAND_LOCK_TIMEOUT`、复合 `bizId={orderId}:{lockSeq}`、抢单事务内注册、`action_time=锁单时刻+5min`、回调条件 UPDATE `LOCKED→PUBLISHED`（`lock_seq` 轮次守卫，未命中即幂等）、不调 cancel、中心零代码改动；参数 `LOCK_TIMEOUT_MINUTES=5`。**计时自锁单时刻**（≠ 支付超时自创建）。
4. **防占单（票 03）**：不新增表/列（复用 `errand_order_event` + 索引 `idx_actor_type_time`）；滑动 24h ≥3 次主动放弃 → 冷却 2h（触发即重置窗口）；Redis ZSET `bs:errand:grab-abandon:{uid}` + `SET NX EX` 冷却键，Redis 不参与状态互斥、异常回落 SQL；**不扣信用分**；**反转票 02** —— 释放后发一条结果通知（`dedupKey` 带 `lockSeq`），仍不做提前预警。
5. **统一审核中台**：数据模型（票 06：`review_task` DDL + `content_review.author_id` + 4 类型 + `claim_state` PENDING/CLAIMED/DONE + 强绑定 `decideTask`）＋派单方案（票 05：presence Redis、`/ws/review`、粗粒度信号、claim 条件 UPDATE 一次一条 FIFO、租约 5min 走超时中心 `REVIEW_LEASE_TIMEOUT` 复合 bizId、惰性兜底、`recycle_count≥3` 升级）＋工程形态（票 08：`front-review/`、替换 `/admin/review`、`/api/moderation/tasks/**`、同域 `/review/` 仅内网、dev 5174、手抄类型）+ 原型结论（票 07）。
6. **术语**：`CONTEXT.md` 新增跑腿 6 条（草稿/锁单/已确认/放弃/占单/接单冷却）+ 审核中台 6 条（审核工单/派单信号/领单/租约/放回/回收）+ 修订 2 处；不加工程/部署词条。
7. **ADR 判定**：落 **2 篇**——`0008` 跑腿状态机扩展（不推翻 ADR-0003，两端插态）、`0009` 统一审核中台（合并工程/派单模型/替换三件同一决策簇）。不复用退役号。
8. **对接影响**：仅**文档回填** `.scratch/timeout-center/spec.md`（§8 补两个接入方、界定 §10）与 `backend/docs/architecture/timeout-center.md`（接入方清单补两条），**中心零代码改动**。
9. **可观测**：本期做＝冷却 WARN 日志、审核四指标 + 积压告警；开放项＝工作量视图、滥用榜单、草稿清理、终态软删、风险标记形态、同单上限、入池兜底对账等。
10. **Out of scope**：从 map 逐条定稿（举报申诉 / 自建账号 / 旧链路下线 / 跑腿执行时限接入 / 实现本身 / 多人单）。

**产出**：[`spec.md`](../spec.md)（Status: accepted）。
