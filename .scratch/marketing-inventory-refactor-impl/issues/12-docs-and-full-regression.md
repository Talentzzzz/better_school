# 12: 文档收口 + 全量回归

**Spec:** `.scratch/marketing-inventory-refactor/spec.md` §11（文档与 ADR）、§12（已知波及）

**What to build:** 把 01~11 落下的代码事实写进文档：库存页重写、抽奖接口文档的几节、`sql.md` 复核、
`CONTEXT.md` 词条、ADR 0005 标 superseded + 新建 ADR-0011、`.scratch/timeout-inline/map.md` 的判据补注。
最后跑一次「重建测试库 + 全量集成测试」，确认没有靠旧 schema 的假绿。

**Blocked by:** 11

**Status:** resolved

**预算：≈150k / 200k。** 文档落点多但每处都短（库存页是重写，其余是改段）。
若预算告急，止损线：`inventory.md` + `lottery.md` + ADR-0011 三件必须先完成，
`CONTEXT.md` / `draw.md` / `timeout.md` 可以同日补齐但不要跳过全量回归。

## 验收：后端文档

- [x] `backend/marketing/docs/db/inventory.md` **重写**：6 张表（8 → 6）、两张明细表的新列/唯一键/两条索引、
      一条 `segment` 参数的代码形状、触发（1 秒延迟消息 + 60 秒兜底）、时间格合并的四条约束、
      两段对账恒等式、Redis key（含 `merge:`）、`state` 两态、兑换确认异步化
- [x] `backend/marketing/docs/README.md`：库存行改成 `virtual_inventory_quota_* 3 张 + physical_marketing_quota_* 3 张`
- [x] `backend/marketing/docs/db/draw.md`：主链路的「预扣 → 合并」触发描述；发货状态机表里「出库台账」与
      `confirmIssued` 的措辞；「兑换确认排在推进之前」整条（4312 退休，顺序保留但不再承重）；
      「合并扫描不连接活动表」改成「合并按 key 合明细、不看活动」
- [x] `backend/marketing/docs/api/lottery.md`：定时任务表删 `inventoryMerge`、加 `inventoryMergeBackstop`（60s）；
      事件表加 `bs-inventory-merge`（生产方 = 消费方 = 本服务 · 库存域，消费组 `bs-marketing-inventory-merge-group`）；
      「发货消费的重投口径」整节改写（异步确认、4312 退休、人工核窗口消失、失败看 `pendingClaimQty`）；
      错误码表删 `4312`；目录 `pointsAmount` 的 JOIN 表名
- [x] `backend/marketing/docs/architecture/timeout.md`：两处 `inventoryMerge` 改成已删除 / 由延迟消息 + 兜底取代
- [x] 表数口径复核（票 01 删两表后这些数字全部过期，且不进上面任何一节）：`backend/docs/db/README.md`、
      `backend/docs/testing.md`、`backend/docs/architecture/marketing-services.md`、
      `backend/marketing/docs/README.md`、`backend/marketing/docs/architecture/timeout.md`
      里的 `22` / `38` / `8 张` 等营销表数改成收口后的实际值。
      同一批文件里作为**表名**出现的 `virtual_inventory`（票 02 已改成 `virtual_inventory_quota`）一并换掉——
      `backend/docs/db/README.md` 与 `backend/docs/architecture/marketing-services.md` 各有一处，
      不在 marketing 模块的 `docs/` 里，容易漏
- [x] 根目录 `sql.md` §2 与 `schema.sql` 复核；`.scratch/project-rework/cmp_schema_marketing.py` 零差异

## 验收：领域文档

- [x] `CONTEXT.md`「**预扣**」：唤醒源改成「1 秒延迟消息 + 60 秒兜底」；补「明细带业务唯一键，撞键 = 已落过」；
      「台账总量此刻不动」保留
- [x] `CONTEXT.md`「**奖品类型**」：实物档的兑换确认改成「落 `type = 2` 明细、由合并转移」，不再同步抛 4312
- [x] `CONTEXT.md`「**超时任务**」的 `_Avoid_` 里「延迟消息」补边界：超时任务不用 MQ 延迟等级；
      库存合并的 1 秒唤醒用，两者不是一回事
- [x] `CONTEXT.md` 新增「**库存配额**」「**时间格合并**」两条词条
- [x] `docs/adr/0005-inventory-merge-aggregate-scan.md`：加 front matter `status: superseded by ADR-0011`
      + 顶部一行指针（反转的是它 Considered Options 的第 2 条与第 4 条的升级路径），原文保留
- [x] `docs/adr/0011-inventory-merge-delay-message.md` 新建：决策（outbox + 延迟级别 1、时间格 + `affected` 判重、
      60s 兜底、按 `type`/`segment` 分叉）、被否选项（每秒全表扫、RDZ/RDQ、DB 唯一键做消息去重、秒级令牌列）、
      Consequences（重复投递、1s 量化、兜底是必需件、同 key 可并行）
- [x] `.scratch/timeout-inline/map.md`：`inventoryMerge` 那条判据末尾追加一行——该 job 已被本 effort 删除，
      改为「1 秒延迟消息 + 60 秒兜底」，判据失效

## 验收：全量回归

- [x] 按 spec §3 重建 `bs_marketing_test`（先 DROP 再灌 `schema.sql`），再跑 `backend/marketing/` 全量集成测试，全绿
- [x] 不重建直接再跑一遍也全绿（证明测试不依赖残留表）
- [x] `grep -rn "physical_inventory" backend/marketing` 只命中允许的历史文档（本票应清零）；
      `grep -rn "virtual_inventory[^_q]" backend/marketing` 无业务代码命中
- [x] `grep -rn "mergeOnce\|inventoryMerge\"" backend/marketing` 无命中

## 实现要点

（2026-10-18 落地。回归 **114 tests 绿 ×2**；`cmp_schema_marketing.py` 逐表逐列全等；三条 grep 在
`--exclude-dir=logs --exclude-dir=target` 下全为 0。**07 仍未落地**，新用例仍在
`VirtualInventoryIntegrationTest` / `PhysicalQuotaIntegrationTest` / `InventoryMergeEngineIntegrationTest`。）

**库存页（重写，43 → 约 150 行）的章节结构**
- 抬头 + **6 张表清单** + 「旧的出入库台账一对表已删除」的原因（同量两本账 → 双扣；OUT 行无读路径）。
- **明细表：业务身份 + 两态 + 两条索引**：一张列对照表（虚拟没有 `type` 是唯一结构性差异）、
  `uk_biz` / `uk_biz_type` 为什么实物必须带 `type`、撞键 = 成功回放、`state` 收 0/1 + `CHECK`、
  `idx_key_cell` 与 `idx_pending` 各自服务谁（含「`idx_key_cell` 前缀是 `inv_id`，兜底全局扫用不上」）。
- **触发**：一张「两个唤醒源」表（延迟消息负责积压 / 兜底只负责消息丢了）+ 载荷三字段 + Lua 两键两参返回
  `{code, notify}` + throttle 1000ms（固定窗口、不删 key、失败回补要删 key）+ 明细与 outbox 同事务 +
  「重投不保留延迟（首次 10s）」+ 续命。
- **合并**：一格的事务五步 + **四条约束**单列（① 一格一事务 ② 按格条件翻转 + `affected == c1+c2`
  ③ 只合最老一格是 `waiting` 守卫的结构前提 ④ 分叉只看表与 `type`，不看 `award_type`）+ 三种失败的
  日志级别与暴露读数表 + 兜底 200 key / 60s。
- **兑换确认（异步）**：回查预扣行的两个入参陷阱（预扣行是 `type=1` / `LOTTERY_DRAW`）、恒 1 条消息、
  撞键回放、`type=2` 从不碰 Redis、4312 退休后只表现为 `pendingClaimQty > 0`、顺序保留但不再承重。
- **预热**（`[quota|virtual:]invId`、SET 语义、在途禁重灌）/ **对账**（两段恒等式 + 报告七字段 +
  只算 `type=1` 的理由）/ **Redis key 表**（桶 key + `merge:` 去重 key）/ **与主链路相接**。
- 全篇只写现状，不写「之后」；旧表名不再出现（连「已删除」的那对表也只按「出入库台账」称呼，不写旧表名）。

**ADR 的两处落点**
- `0005`：文件头加 `--- status: superseded by ADR-0011 ---` front matter，标题下加一段引用块指针，
  点名反转的是 **Considered Options 第 2 条**（MQ 延迟消息，原文以「引入 MQ 是本次明确排除的方向」否决）
  与 **第 4 条**（RDQ 作升级路径，原文写「pending 行数涨到每秒聚合扫不动时…只替换工作清单来源」）；
  同时点明第 1/3/5 条的否决理由仍然成立。正文一字未改。
- `0011`：`# 标题` → 为什么难逆 / 为什么反直觉 / 为什么是真权衡 → `## Decision`（唤醒、延迟级别随消息持久化、
  工作清单 = 最老时间格、判重、兜底、分叉、异步确认共 7 条）→ `## Considered Options`（保持每秒全表扫、
  RDZ/RDQ、DB 唯一键做消息去重、秒级令牌列、认领态/锁、Spring 事件共 6 条）→ `## Consequences`
  （重复投递常态、1s 量化、兜底是必需件、不变量破坏不重试、运维口径、outbox 多一维、升级判据）。
  **不抄 SQL**，实现细节指向 `docs/db/inventory.md`。

**表数口径（8 → 6 之后的连锁）**
`bs_marketing` 由 **22 → 20** 张（删 2 张出入库台账）：`marketing/docs/README.md`、`backend/docs/db/README.md`、
`backend/docs/testing.md`、`backend/docs/architecture/marketing-services.md`、`marketing/docs/architecture/timeout.md`
（含「22 → 38」改成「20 → 36」、两处「22 张业务表」、§6 的 handler 清单 `inventoryMerge` → `inventoryMergeBackstop`）。
`virtual_inventory` 作为表名的 6 处（含 `lottery.md` 三处口径）全部换成 `virtual_inventory_quota`。
`timeout.md` §5 的「三个扫描 job」表删掉 `inventoryMerge` 行、改写成一段「已被删除」说明，
§0 的「三个不迁」与结尾「不要做」清单同步收成两个。

**`sql.md` §2 的两段「说明」**
原来写「XXL-Job 每秒触发：先扫 activity → strategy → inv_id → bucket_id…」，与现状完全不符（还带着旧的活动表连接），
改为「预扣同事务写一条延迟 1 秒的合并消息 → 消费侧按 `(inv_id, bucket_id)` 合最老时间格 → 60 秒兜底」，
实物段补 `type=1` / `type=2` 的分叉与「只合最老一格保证顺序」。表结构本身零改动（票 01~04 已同步）。

**两次全量回归**
1. 重建：`DROP DATABASE bs_marketing_test` + `CREATE` + 容器内 `mysql < schema.sql`（20 张表已核）；
   `mvn -o -B test` → **114 tests 绿**，`Total time: 02:30`。
2. 不重建再跑：**114 tests 绿**，`Total time: 01:48`（无残留表依赖；本轮少了建表与首次连接预热）。
   两次都覆盖抽奖侧三条链路：`RaffleDrawPipelineIntegrationTest`（13）、`RaffleShippingConsumerIntegrationTest`（7）、
   `RaffleShippingOutboxIntegrationTest`（6，共享 sender 的回归哨兵照旧绿）。

**grep 的三条结果**
- `physical_inventory`：**0**（先把 `inventory.md` 与 `lottery.md` 里两处「历史备注」也改掉——否则会命中，
  历史事实留在 ADR / spec / 票里）。
- `virtual_inventory[^_q]`：**0**（`lottery.md` 三处、`backend/docs` 两处、`inventory.md` 一处全换）。
- `mergeOnce|inventoryMerge"`：源码 / 文档 / SQL **0**；唯一命中是
  `backend/marketing/logs/dev-stack-marketing.log` 里 4 行**旧的运行时栈**（gitignore 的生成物，不是代码）。

**刻意保留**
- `lottery.md` 与 `draw.md` 各留一两句「4312 已退休 / 不再抛」，因为它是这次唯一被删掉的错误码，
  读者（尤其运维与前端）会照着旧文档找它；`错误码表` 本身已无 4312 行。
- `timeout.md` 保留「每秒的 `inventoryMerge` 已删除」的叙述（票面要求「改成已删除 / 由延迟消息 + 兜底取代」，
  直接抹掉会让「为什么这里只剩两个 job」无从解释）。

## 注意

- 文档写的是**最终行为**，不要照抄 spec 的「之后」措辞（spec 是决策档，文档是现状档）。
- ADR-0011 只记「为什么」，不抄实现 SQL；SQL 在 `docs/db/inventory.md`，两处不要各写一份演进史。
- 全量回归最容易漏抽奖侧：`RaffleDrawPipelineIntegrationTest` / `RaffleShipping*` 三条链路都过一遍库存。
