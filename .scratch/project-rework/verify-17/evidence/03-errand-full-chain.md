# 项 3 — 跑腿单人单全流程（含超时回调）

**判定：PASS**（脚本 `smoke_03_04_errand_chat.py` 项 03A + 03B 共 33 条断言全过；连续跑 3 次均 0 FAIL，可重复执行）
**证据脚本**：[`smoke_03_04_errand_chat.py`](../smoke_03_04_errand_chat.py)（`python .scratch\project-rework\verify-17\smoke_03_04_errand_chat.py`）
**号段**：发布方 `13700017301`（userId 6）、接单方 `13700017302`（userId 7）、审核员 `13700017399`（userId 8，`bs_user.role` 提权 ADMIN）

## 1. 全链路（03A，orderId=7）

真 HTTP 全跑，逐步断言 `errand_order.state`：

| 步 | 接口 | 断言后状态 |
| --- | --- | --- |
| 上传配图 | `POST /api/files/upload`（multipart，`h.upload_png`） | — |
| 发单 | `POST /api/errand/orders`（带 images，reward=10） | `PENDING_PAYMENT` |
| 支付 | `POST /api/errand/orders/7/pay` | `PENDING_REVIEW` |
| 审核通过 | `POST /api/admin/reviews/decide`（ADMIN token） | `PUBLISHED` |
| 接单 | `POST /api/errand/orders/7/grab`（接单方） | `TAKEN` |
| 取件 | `POST .../pickup` | `PICKED_UP` |
| 送达 | `POST .../deliver` | `DELIVERED` |
| 确认 | `POST .../confirm`（发布方） | `COMPLETED` |

> 先付后审（ADR-0003，`ErrandOrderServiceImpl.payOrder`）：支付只转 `PENDING_REVIEW`，`grabOrder` 要求 `PUBLISHED`，
> 因此链路中间**必然**夹一步管理员裁决；本脚本走的是真实裁决接口（不是改库造态）。裁决用的 ADMIN 由
> `h.promote_admin()`（`UPDATE bs_user SET role='ADMIN'` + 重登换 JWT）取得，是 smoke_lib 既有的造态口径。

`errand_order_event` 逐行核对（`ORDER BY id`，与期望元组**完全相等**，含 `from_state`/`to_state`/`actor_id`/`actor_role`）：

```
CREATED        <NULL>            → PENDING_PAYMENT  actor 6  PUBLISHER   ← 唯一 from_state IS NULL 的创建行
PAID           PENDING_PAYMENT   → PENDING_REVIEW   actor 6  PUBLISHER
REVIEW_APPROVED PENDING_REVIEW   → PUBLISHED        actor 8  ADMIN
TAKEN          PUBLISHED         → TAKEN            actor 7  TAKER
PICKED_UP      TAKEN             → PICKED_UP        actor 7  TAKER
DELIVERED      PICKED_UP         → DELIVERED        actor 7  TAKER
CONFIRMED      DELIVERED         → COMPLETED        actor 6  PUBLISHER
```

共 7 行 = 7 次流转（一次流转恰一行），`from_state IS NULL` 的行恰一行且为 `CREATED`。配图从详情接口回读一致，`taker_id` 落接单方。

## 2. 超时回调（03B，orderId=8）——用的机制

**机制（脚本注释与 JSON 里都写了 `mechanism` / `seam` 两个字段）：**
1. **不干等 15 分钟真实支付窗口**：只把**中心侧物理分表行**的 `action_time` / `next_fire_time` 回拨到 `now-2min`
   （`UPDATE bs_timeout.<该行所在分表> SET action_time=..., next_fire_time=... WHERE task_type='ERRAND_PAY_TIMEOUT' AND biz_id=8`）——
   这与集成测试用「回拨到期时间」造到期的 seam 同性质，**没有**碰单体任何表、没有碰中心代码。
2. 回拨后**仍由真实的 xxl 扫描 job 干完活**：`timeoutTaskScanJob`（`0/10 * * * * ?`，执行器组 `timeout-center-executor`）
   自然 10s 周期扫到该行 → 中心按行上的 `callback_group=better-school` 动态建 Dubbo 引用 →
   回调单体 `ITimeoutTaskCallback`（tri 50051）→ `ErrandOrderCase.handlePayTimeout` → 条件 UPDATE 关单。

**判别依据（证明改判来自超时中心，而不是单体自身的 15 分钟兜底扫描）：**
- 订单 `create_time` 距改判仅 **0 分钟**（`TIMESTAMPDIFF < 15`），单体 `ErrandTimeoutScanJob`（每分钟，阈 15 分钟）本单上不可能触发；
- 中心侧任务行从 `WAITING` → **`SUCCESS`**（回调必须被单体成功回报才会 SUCCESS；失败会停在 `RETRYING` 退避）；
- 单体日志出现 `[ErrandPayTimeout] 待支付订单超时取消 orderId=8`（`ErrandOrderServiceImpl`，回调线程 `eout-callback-0`）。

**结果：** `errand_order.state = CANCELLED`，事件流水恰两行
`CREATED(<NULL>→PENDING_PAYMENT, PUBLISHER)` + `PAY_TIMEOUT(PENDING_PAYMENT→CANCELLED, actor_id NULL, SYSTEM)`；
`timeout_register_outbox` 行 `state=SENT / callback_group=better-school`；
中心死信面 `GET http://localhost:8083/api/admin/timeout-tasks/dead-letters?page=1&pageSize=10` → HTTP 200、`total=0`（本任务未进死信）。

**中心侧分表落点：** 本次落在 `timeout_task_08`；脚本对 **16 张分表逐张查**（不假设 `_00`），前两次运行分别落在 `timeout_task_02` / `timeout_task_04`。

## 3. 证据文件（本次运行，`.scratch/project-rework/verify-17/evidence/`）

| 文件 | 内容 |
| --- | --- |
| `03a-errand-full-chain.json` | 发布/接单/审核员 id、orderId=7、配图 URL、状态轨迹、`errand_order` 行、7 行事件流水、详情响应 |
| `03b-timeout-task.json` | orderId=8、**机制与 seam 自述**、分表名 `timeout_task_08`、回拨前后任务行、终态 `SUCCESS`、注册 outbox、订单行（create/update time）与 2 行事件流水、死信首页 |
| `03b-bs-log-errand-pay-timeout.txt` | 单体 `[ErrandPayTimeout] ... orderId=8` 关单日志行 |
| `03b-tc-log-lines.txt` | 中心日志中本次回调相关行（为该任务动态建 `ITimeoutTaskCallback`(group=better-school) 引用，时间戳与扫描 tick 一致） |
| `03-04-summary.json` | 总体 `passed=57 / failed=[]` |

## 4. 失败项与判定

**03A/03B 无失败项**，故无需「编排 vs 业务」判定。两点旁注：

- **工具面（非业务缺陷，已由父代理修掉）**：`smoke_lib.Harness.promote_admin()` 原先写 `WHERE mobile=...`，而 `bs_user` 的列名是 `phone`，
  首次尝试会 SQL 失败。现已改为 `phone`，本脚本直接调库里的实现。
- **脚本自身缺陷（已修）**：首轮 04 段用 `h.login()` 拉未注册的对家号（404 用户不存在）→ 已改为 `h.register()`（对已注册号自动回落登录），
  04 段现在可脱离 03A 独立运行。
