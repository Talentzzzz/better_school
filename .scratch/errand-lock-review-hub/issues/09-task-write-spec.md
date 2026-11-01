# 09 task：spec 定稿（effort 收口）

Type: task
Status: open
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
8. **对接影响**：对 `.scratch/agent-review/spec.md`、`.scratch/timeout-center/spec.md`、`backend/docs/architecture/timeout-center.md` 的回填点。
9. **可观测与运维**：本期做的与开放项分开标注。
10. **Out of scope**：从 map 抄下，逐条定稿。

**产出**：`spec.md` 定稿（Status: accepted）。**本票关闭 = 地图抵达终点**。

## Answer
