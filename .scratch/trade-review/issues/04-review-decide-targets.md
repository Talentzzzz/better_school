Status: ready-for-agent
Type: task
Blocked by: 01, 03

# 04: 统一裁决接入——商品可见、求购可见、拒绝各有出路

**What to build:** 把商品与求购接入统一裁决用例 `decide(targetType, targetId, approve, reason, operatorId)`：目标类型新增商品与求购两类。商品：通过 → 条件 UPDATE 转 ON_SALE（对外可见）；拒绝 → REJECTED（无资金环节，不涉及退款）。求购：通过 → NORMAL（对外可见）；拒绝 → REJECTED 终态 + **解冻预算全额退回**（流水先行 + 唯一键幂等，对应跑腿的退款链路）。拒绝均需 reason 必填、通知发布者（含理由）、详情对发布者带出拒绝理由。`content_review` 的审核记录幂等键加入审核轮次，使同一目标可被多次审核而不冲突（商品转草稿重提、下架重发都会产生第二次审核）。管理端审核队列带出待审商品/求购及其配图，复用既有看图裁决能力；agent 以系统账号 operatorId 复用的约定同样生效。

- [x] 商品通过：待审 → ON_SALE，对外可见，发布者收到通过通知
- [x] 商品拒绝：无 reason 被拒；有 reason → REJECTED + 通知含理由 + 详情带理由
- [x] 求购通过：待审 → NORMAL，对外可见，发布者收到通过通知
- [x] 求购拒绝：REJECTED 终态 + 预算全额解冻退回 + 通知含理由 + 详情带理由；重复裁决不重复退款
- [x] 审核队列同时展示待审商品与待审求购，并带出配图供看图裁决
- [x] 同一目标两次裁决的审核记录互不冲突（轮次幂等），拒绝理由按最新一次展示
- [x] 非管理员调用被拒；operatorId 传系统账号可走通（agent 预留约定）
- [x] 既有 POST / COMMENT / ERRAND 裁决行为无回归
