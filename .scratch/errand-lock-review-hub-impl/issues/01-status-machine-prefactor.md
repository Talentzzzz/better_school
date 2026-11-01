# 01: 跑腿状态机骨架（枚举扩展 + `TAKEN`→`CONFIRMED` 改名 + `lock_seq` 列）

**Spec:** `.scratch/errand-lock-review-hub/spec.md` §2.3（枚举与字段变更）、§2.4（存量迁移）

**What to build:** 把跑腿订单状态机的词汇与列先准备好，后续票不必再回头改名。`ErrandStatusVO` 落到 13 值——新增 `DRAFT` / `LOCKED` / `DELETED`，`TAKEN` **改名** `CONFIRMED`；同批替换全仓状态字面量：持久层条件 UPDATE 的 SQL、作者主页展示口径（`PUBLISHED`/`TAKEN` → 含新值）、搜索侧「可搜状态集」、前端状态中文文案、以及既有集成测试里的状态断言。`errand_order` 新增 `lock_seq INT NOT NULL DEFAULT 0`（每次抢单 +1，既是超时中心复合 `bizId` 的轮次、也是回调守卫的比对值），`state` 列注释补新值。同时修掉事件撞名：`DELIVERED→COMPLETED` 那笔流转的事件类型由 `CONFIRMED` 改名为 `COMPLETED`（自动完成仍是 `AUTO_COMPLETED`）。

**为什么是单票**：这是一次机械化宽改名（blame 面铺满全仓），但本仓无存量数据、调用点由编译器与测试兜底，改名可一次落地并保持全绿，不需要 expand–contract 两段式。

**口径**：无编号迁移脚本——改 `schema.sql` 后重建库即生效。本期无 `REJECTED→DRAFT` 复活路径。

**Blocked by:** None (can start immediately)

**Status:** resolved（commit 48a3ae4）

- [x] 状态枚举 13 值齐备，`TAKEN` 字面量在仓库内（后端 SQL / 前端 / 测试 / 文档同口径）零残留，改为 `CONFIRMED`
- [x] `errand_order.lock_seq` 列随 `schema.sql` 落库（`NOT NULL DEFAULT 0`），重建库通过
- [x] 前端状态文案：`草稿` / `锁单中` / `已确认`，`PUBLISHED` 维持「待接单」；`DELETED` 不对外展示（列表/详情一律排除，无需文案）
- [x] `DELIVERED→COMPLETED` 事件类型名为 `COMPLETED`（不再与新 `CONFIRMED` 状态撞名）
- [x] 全仓构建绿 + 既有跑腿集成测试全绿；无任何新行为引入（本票纯词汇/列准备）
