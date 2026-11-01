---
Labels: wayfinder:task
Type: task
Status: resolved
Blocked by: 02, 04, 05, 06, 07, 08
---

# 票 09：spec 定稿与落地票拆分

## Question

收口：把票 01–08 的决策汇总为 `.scratch/chat-refactor/spec.md`（定稿 DDL、长连接方案、消息同步语义、接口清单、群聊规则、前端改造点），并按既有流程拆好落地实施票（实现票一票一文件，从后续 effort 起点开始编号）。CONTEXT.md 的 chat 术语在此统一校对；地图「Not yet specified」此时清零或明确移交。

➡️ 产出：spec.md + 落地票列。本票关闭即地图走完。

## Answer

（2026-09-09，地图走完）

- **[spec.md](../spec.md) 定稿**：汇总票 01–08 全部决策——§1 DDL（原型 + `message.client_msg_id` + `uk_client_msg` 修订，落地并入 init.sql）、§2 消息同步语义（双序号空间/发送幂等/判重/补拉口径/已读时序/异常路径表）、§3 长连接（裸 WS/协议信封/状态机/参数）、§4 接口清单（REST 十一端点 + WS 六类帧 + 系统侧领域 API）、§5 群聊规则、§6 零审核+举报、§7 前端七改造点、§8 容量估算固化（800 万 timeline 行/天 ≈ 0.8GB、峰值 ~900 写/秒单机可接受；演进口三条：timeline 归档/群扇出转异步/前端 delivery_seq localStorage）、§9 测试策略、§11 术语校对。
- **落地票列**：`.scratch/chat-impl/issues/` 01–10（依赖链 01→02→03→04→{05,06}→07、04→08→09→10），见 [chat-impl map](../../chat-impl/map.md)。
- **CONTEXT.md**：chat 术语 21 条已随各票沉淀，本票校对「消息序号」一处（增量同步改由投递序号承担，票 04 更正口径）。
- **map 遗留项**：全部关闭（写放大量化入 spec §8，其余此前已闭），Not yet specified 清零。
