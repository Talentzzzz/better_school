# 12: 文档收口 + 全量回归

**Spec:** `.scratch/marketing-inventory-refactor/spec.md` §11（文档与 ADR）、§12（已知波及）

**What to build:** 把 01~11 落下的代码事实写进文档：库存页重写、抽奖接口文档的几节、`sql.md` 复核、
`CONTEXT.md` 词条、ADR 0005 标 superseded + 新建 ADR-0011、`.scratch/timeout-inline/map.md` 的判据补注。
最后跑一次「重建测试库 + 全量集成测试」，确认没有靠旧 schema 的假绿。

**Blocked by:** 11

**Status:** ready-for-agent

**预算：≈150k / 200k。** 文档落点多但每处都短（库存页是重写，其余是改段）。
若预算告急，止损线：`inventory.md` + `lottery.md` + ADR-0011 三件必须先完成，
`CONTEXT.md` / `draw.md` / `timeout.md` 可以同日补齐但不要跳过全量回归。

## 验收：后端文档

- [ ] `backend/marketing/docs/db/inventory.md` **重写**：6 张表（8 → 6）、两张明细表的新列/唯一键/两条索引、
      一条 `segment` 参数的代码形状、触发（1 秒延迟消息 + 60 秒兜底）、时间格合并的四条约束、
      两段对账恒等式、Redis key（含 `merge:`）、`state` 两态、兑换确认异步化
- [ ] `backend/marketing/docs/README.md`：库存行改成 `virtual_inventory_quota_* 3 张 + physical_marketing_quota_* 3 张`
- [ ] `backend/marketing/docs/db/draw.md`：主链路的「预扣 → 合并」触发描述；发货状态机表里「出库台账」与
      `confirmIssued` 的措辞；「兑换确认排在推进之前」整条（4312 退休，顺序保留但不再承重）；
      「合并扫描不连接活动表」改成「合并按 key 合明细、不看活动」
- [ ] `backend/marketing/docs/api/lottery.md`：定时任务表删 `inventoryMerge`、加 `inventoryMergeBackstop`（60s）；
      事件表加 `bs-inventory-merge`（生产方 = 消费方 = 本服务 · 库存域，消费组 `bs-marketing-inventory-merge-group`）；
      「发货消费的重投口径」整节改写（异步确认、4312 退休、人工核窗口消失、失败看 `pendingClaimQty`）；
      错误码表删 `4312`；目录 `pointsAmount` 的 JOIN 表名
- [ ] `backend/marketing/docs/architecture/timeout.md`：两处 `inventoryMerge` 改成已删除 / 由延迟消息 + 兜底取代
- [ ] 表数口径复核（票 01 删两表后这些数字全部过期，且不进上面任何一节）：`backend/docs/db/README.md`、
      `backend/docs/testing.md`、`backend/docs/architecture/marketing-services.md`、
      `backend/marketing/docs/README.md`、`backend/marketing/docs/architecture/timeout.md`
      里的 `22` / `38` / `8 张` 等营销表数改成收口后的实际值。
      同一批文件里作为**表名**出现的 `virtual_inventory`（票 02 已改成 `virtual_inventory_quota`）一并换掉——
      `backend/docs/db/README.md` 与 `backend/docs/architecture/marketing-services.md` 各有一处，
      不在 marketing 模块的 `docs/` 里，容易漏
- [ ] 根目录 `sql.md` §2 与 `schema.sql` 复核；`.scratch/project-rework/cmp_schema_marketing.py` 零差异

## 验收：领域文档

- [ ] `CONTEXT.md`「**预扣**」：唤醒源改成「1 秒延迟消息 + 60 秒兜底」；补「明细带业务唯一键，撞键 = 已落过」；
      「台账总量此刻不动」保留
- [ ] `CONTEXT.md`「**奖品类型**」：实物档的兑换确认改成「落 `type = 2` 明细、由合并转移」，不再同步抛 4312
- [ ] `CONTEXT.md`「**超时任务**」的 `_Avoid_` 里「延迟消息」补边界：超时任务不用 MQ 延迟等级；
      库存合并的 1 秒唤醒用，两者不是一回事
- [ ] `CONTEXT.md` 新增「**库存配额**」「**时间格合并**」两条词条
- [ ] `docs/adr/0005-inventory-merge-aggregate-scan.md`：加 front matter `status: superseded by ADR-0011`
      + 顶部一行指针（反转的是它 Considered Options 的第 2 条与第 4 条的升级路径），原文保留
- [ ] `docs/adr/0011-inventory-merge-delay-message.md` 新建：决策（outbox + 延迟级别 1、时间格 + `affected` 判重、
      60s 兜底、按 `type`/`segment` 分叉）、被否选项（每秒全表扫、RDZ/RDQ、DB 唯一键做消息去重、秒级令牌列）、
      Consequences（重复投递、1s 量化、兜底是必需件、同 key 可并行）
- [ ] `.scratch/timeout-inline/map.md`：`inventoryMerge` 那条判据末尾追加一行——该 job 已被本 effort 删除，
      改为「1 秒延迟消息 + 60 秒兜底」，判据失效

## 验收：全量回归

- [ ] 按 spec §3 重建 `bs_marketing_test`（先 DROP 再灌 `schema.sql`），再跑 `backend/marketing/` 全量集成测试，全绿
- [ ] 不重建直接再跑一遍也全绿（证明测试不依赖残留表）
- [ ] `grep -rn "physical_inventory" backend/marketing` 只命中允许的历史文档（本票应清零）；
      `grep -rn "virtual_inventory[^_q]" backend/marketing` 无业务代码命中
- [ ] `grep -rn "mergeOnce\|inventoryMerge\"" backend/marketing` 无命中

## 实现要点

（落地时填：库存页的新章节结构、ADR 的被否选项与被反转的原文位置、两次全量回归的耗时）

## 注意

- 文档写的是**最终行为**，不要照抄 spec 的「之后」措辞（spec 是决策档，文档是现状档）。
- ADR-0011 只记「为什么」，不抄实现 SQL；SQL 在 `docs/db/inventory.md`，两处不要各写一份演进史。
- 全量回归最容易漏抽奖侧：`RaffleDrawPipelineIntegrationTest` / `RaffleShipping*` 三条链路都过一遍库存。
