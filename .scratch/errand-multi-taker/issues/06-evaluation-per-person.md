# 06: 评价按人

**What to build:** 多人单完成后评价从"一单一评"改为按人：发布方逐一评价每个接单者，每个接单者评价发布方；`errand_evaluation` 加 to_user 维度（唯一键扩展为 order + from + to），好评 +2 按人按次计入信用（每日上限不变）；前端评价 UI 从单评改为逐人列表评价。单人单评价行为不变（to_user 即唯一对方）。

**Blocked by:** 05

**Status:** resolved

- [x] 评价接口带 to_user；发布方可分别评价每个接单者，接单者评发布方；重复评价按唯一键幂等拒绝
- [x] 好评加分按人按次落信用流水（uk_user_reason_ref 幂等），每日上限口径不变
- [x] 增量迁移：to_user 列与唯一键重建，存量单人单评价数据兼容
- [x] 前端：完成后逐人评价列表（未评/已评状态），单人单 UI 无感知
- [x] 集成测试：逐人评价、唯一键幂等、加分幂等、单人单回归

## Comments

**按人评价落在 domain 一处解析被评方**（`ErrandOrderServiceImpl.resolveEvaluationTarget`）：`evaluate(orderId, fromUserId, toUserId, score, content)`——发布方 → 单人单唯一 `taker_id`（入参缺省即推导，接口向后兼容）/ 多人单**在团成员**（`findMember` + `inTeam`，逐一评价）；接单方 → 恒为发布方（入参非发布方即拒）。越权/非成员/接单者评其他接单者一律拒。接口 `ErrandEvaluationRequestDTO` 加 `toUserId`（多人单必填、单人单可缺省）。

**唯一键按人（迁移 020）**：`uk_order_from (order_id, from_user_id)` → `uk_order_from_to (order_id, from_user_id, to_user_id)`。票面写「to_user 列与唯一键重建」，实则 `to_user_id` 自初始 schema 即存在（随 init.sql 交付、无独立迁移，现有 INSERT 已写该列），故迁移只重建唯一键 + 刷列注释，存量数据无需回填（旧键是新键子集，单人单 to_user 恒唯一对方）。迁移测试实证「存量保留 + 同单同评价者不同被评者各落一条 + 同被评者重复被拒」。

**好评加分按人按次：幂等引用改用评价行 id**：`user_credit_log.ref_id` 由 orderId 改为 **evaluationId**。原因：uk 是 `(user_id, reason_type, ref_id)`，多人单里**发布方被 N 个接单者各评一次**，若 ref 仍用 orderId 则第 2..N 条会撞 uk 静默吞掉（发布方只 +2 一次），违背 spec「好评 +2 各自计」。改用评价 id 后每人每次一条、重复提交/重试撞同一 id 幂等跳过；`isGood` 幂等路径返回**既有评价的分数**（而非本次入参），避免改分重试误加分。每日上限（10）走 `applyChange` 原逻辑，口径不变（上限测试：6 人好评只落 5 条 +10）。

**前端逐人评价列表**：`evalTargets` computed 解析「我能评谁」——发布方 → 多人单全部在团成员 / 单人单唯一接单者；接单方 → 发布方；`evaluatedTo(userId)` 按 (from=我, to=该人) 判定未评/已评。评价弹层带被评者昵称标题、提交带 `toUserId`。单人单退化为一行列表（同一入口、行为等价）。**顺带修掉既有缺陷**：`listEvaluations` 前端按数组用（`.find`/`v-for`）而后端返 `PageResult`，原来一进已完成单人单详情即 `.find is not a function` 崩渲染——改为读 `.list` 并按 `pageSize=50` 一次取满（一单最多 2×10=20 条，避免分页漏行导致「已评」误判）。

**code-review 采纳**：①`resolveEvaluationTarget` 复用新增的 `inTeam` 助手（原内联了同一判定）；②评价列表前端取满一页（上述分页漏行可达缺陷）。**留档未改**：①**退团/被补位换下的成员不能评价**——「在团成员」= 完成结算的那批人（与票 05 结算范围一致），spec 对退出者评价未表态，取完成群体为界；②并发重复提交撞 uk 会以 500 暴露、客户端重试即幂等成功——符合 backend/CLAUDE.md 「HTTP 路径返回失败让客户端重试」的恢复口径（评价非资金/信用，不强制 INSERT IGNORE），顺序重试由 `findEvaluation` 前置幂等兜住；③`toNickname`（DTO/PO/JOIN/前端）与互评列表「评价者 → 被评者」后缀是票面外的可读性补强（多人单同人多条评价否则无法分辨对象）；④单人单评价入口由动作栏按钮改为一行列表（功能等价，UI 微调）。

**验收**：全量后端 **304 绿**（新增 `ErrandMultiTakerEvaluationIntegrationTest` 5 例：发布方逐一评/接单者评发布方按人加分、重复评价幂等不重复加分、越权与非成员拒绝、好评每日上限不变、单人单回归；新增 `ErrandEvaluationPerPersonMigrationTest` 1 例），前端 `vue-tsc` + `vite build` 绿。双端 UI 冒烟（真实 MQ 链路）：2 人单成团（room 9）→ 取货/送达/发布方确认 → COMPLETED；浏览器以接单者 A 视角见「评价发布者」逐人卡（smoke06_pub + 评价）→ 好评提交 → 「已评价」+ 互评「smoke06_a → smoke06_pub」；切发布方视角见「评价接单者」两人各自 未评/已评（评 A 后 A 行转已评价、B 行仍可评）→ 互评两条按名可辨；库内 2 条评价（pub↔a）+ 2 条 EVALUATION_GOOD（ref_id = 各评价 id，按人各 +2）。冒烟数据（订单/群/用户/流水/评价/通知/outbox）已删还原，`smoke-ticket06-prep.py` 留档（token json 已删不入库），迁移 **020 已应用联调库**。
