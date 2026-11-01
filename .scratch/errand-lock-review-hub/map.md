# Map：跑腿锁单 + 统一审核中台

_Label: `wayfinder:map`_

## Destination

一份 spec：`.scratch/errand-lock-review-hub/spec.md`，定义两件事的完整落地设计——拆票即可开发，**不含实现本身**：

1. **跑腿订单状态机扩展**：新增 `草稿` 与 `锁单` 两态、`已确认` 收编现有 `TAKEN`，配 5 分钟锁单超时（接超时中心）与防恶意占单规则。
2. **统一审核中台**：独立前端工程 `front-review/`，同屏审多种内容（帖子/评论/商品/跑腿），工单按「推信号 + 拉工单」派给在线审核员并带租约回收。

## Notes

- **领域**：better_school 校园平台。全站 UGC 已有 `content_review`（多类型，帖子/评论/商品/跑腿）+ 人工工作台 `front/src/views/admin/ReviewWorkbenchView.vue`（路由 `/admin/review`，ADMIN/AUDITOR 门控）。**本 effort 的审核中台是全站内容的人工审核工作面**——人工为唯一裁决者，派单池即全量待审。
- **后端**：Spring Boot 3.3.5 / JDK 21 多模块 DDD（`backend/CLAUDE.md`）。跑腿域 `bs-domain/.../errand`（状态枚举 `ErrandStatusVO.java`）、用例 `bs-case/.../ErrandOrderCase.java`、域服务 `ErrandOrderServiceImpl.java`。
- **超时中心已就绪**（`backend/timeout-center/`，HTTP 8083 / Dubbo tri 50054）：业务注册走 `timeout_register_outbox`（事务内写 + afterCommit 发 + 30s 扫描兜底），回调走 `ITimeoutTaskCallback`（按 `callbackGroup` 动态路由），**at-least-once，幂等在业务方（状态机条件 UPDATE）**；跑腿创建已接 `ERRAND_PAY_TIMEOUT`。详见 `backend/docs/architecture/timeout-center.md`、`.scratch/timeout-center/spec.md`。
  - ⚠️ 超时中心 spec 的 Out of scope 原写「跑腿执行时限、组队截止不接」——**锁单超时是新增类型**，本 effort 需要在接入时补一条约定（不推翻该 spec 的通用模型：任意 `task_type + payload + 回调`）。
- **工作方式**：grilling 票每轮问 frontier、每问给推荐答案；prototype 票产出可点原型；决策票被前置票阻塞（`Blocked by:` 行）。
- **既有关联决策**：ADR-0003（跑腿先付后审，本 effort 沿用）、`.scratch/errand-multi-taker/spec.md` Q4（**成团自动开始、不设手动确认**）——本 effort 的「先抢后确认」与它冲突，票 01 必须裁决是否全局反转或只对单人单生效。
- 求职作品，真实产品标准。

### 图绘期已定决策（2026-10-13 访谈两轮）

- **状态机**：`草稿 → 待支付 → 已支付待审核 → 已发布 → 锁单 → 已确认 → 取货 → 送达 → 完成`（终态另有 `CANCELLED / DISPUTED / REJECTED`）。
  - `草稿` 是**新增态**（可编辑可删、不付款、不对外）；`待支付 / 已支付待审核 / 已发布` 沿用现有 `PENDING_PAYMENT / PENDING_REVIEW / PUBLISHED`。
  - `锁单` 是**新增态**（抢到即锁、**非开工**）；`已确认` = 现 `TAKEN` 改名，其后链路不变。
  - 「确认」是**接单方**核对信息后自己点；**不合适可放弃**，放弃回 `已发布`（他人可抢）。
- **付款/审核位置**：沿用 **ADR-0003 先付后审**（不反转）。
- **锁单超时**：**5 分钟**，**复用既有超时中心**（不自建扫描 job）。
- **审核内容类型**：**全做**（帖子/评论/商品/跑腿）。⚠️ **求购已随产品整体删除**（2026-10-14 用户确认）——后端从无求购实体，本 effort 不含求购；工单类型复用 `ReviewTargetTypeVO` 的 4 个可审值。
- **地图数量**：**一张 map** 打通两块（内部两条工作流）。
- **派单模型**：**推信号 + 拉工单**——推送只负责唤醒（广播「有新活」，不指定给谁、不锁单），拉取负责取活（审核员手里审完才 `claim` 拉下一条，服务端原子指派 + 租约）。不做「抢审」（审核不像外卖，抢无意义），不做纯指派（无法适配审核员快慢差异），不用纯拉（丢时效）。
- **基础设施态度**：Redis / WebSocket **有无都不阻塞**（缺什么建什么）；审核员人数**不是约束**。

## Decisions so far

<!-- 索引：每条已关闭的票一行，够判断相关性即可，细节点链接进票面 -->

- [01 grilling：跑腿新状态机定稿](issues/01-grill-errand-state-machine.md)：**枚举定稿**（新增 `DRAFT`/`LOCKED`/`DELETED`，`TAKEN` 改名 `CONFIRMED`，共 13 值）＋**完整迁移矩阵**（含锁单期交叉裁决：发布方**可**取消、**不进**仲裁、执行超时白名单加 `LOCKED`）＋**放弃不扣信用分**（与「取消」分事件）＋**单人单不分叉**（多人单已下线）＋**无存量 ⇒ 零迁移脚本**（票面「迁移已到 006」已过时，仓库无 `migrations/`）＋CONTEXT 新增 4 词条（含 `_Avoid_`）+ 新增一篇 ADR（编号待票 09 定；0005–0007 均曾占用后删除，建议 0008，不推翻 ADR-0003）。另修事件撞名：旧 `TAKEN`→`LOCKED`、旧 `CONFIRMED`（完成）→`COMPLETED`。**已与票 02 对齐**（加 `lock_seq` 列、不另加时间列、`lock_seq` 回调守卫、`markTimeout`/`listTimeoutOrderIds` 补 `LOCKED`、释放不落搜索同步）。详见 [票面 Answer](issues/01-grill-errand-state-machine.md)。

- [02 grilling：锁单 5 分钟超时接入超时中心](issues/02-grill-lock-timeout.md)：裁决**不复用 `uk(task_type,biz_id)` 同键复位语义**——锁单超时按**锁单轮次**区分（`bizId={orderId}:{lockSeq}`，`errand_order` 加 `lock_seq`），否则「放弃→重抢」的计时重启不可靠（中心对在途键「幂等不改期」，最坏产生**永久卡死的锁**）。回调条件 UPDATE `LOCKED→PUBLISHED`（带 `lock_seq` 轮次守卫，未命中即幂等成功）+ 同事务落 `errand_order_event`；**不调 cancel**、**不发通知 / 不计滥用 / 不扣分**（归票 03）；**中心零代码改动**，只回填 `timeout-center` spec / architecture 文档。taskType `ERRAND_LOCK_TIMEOUT`；计时自**锁单**时刻（≠ 支付超时自**创建**）。转票锚点：票 01 需加 `lock_seq` 列并给 `markTimeout` 补 `LOCKED` 态。详见 [Answer](issues/02-grill-lock-timeout.md)。

- [04 research：既有实时通道与在线状态设施盘点](issues/04-research-realtime-presence.md)：chat 已实装一套裸 WS 连接层（`bs-trigger` `/ws/chat`，票据/注册表/心跳/互踢齐全）**连接层骨架可复用**；Redis 已就绪并已在用（Redisson，含 `RLock` 先例）**presence 与 claim 锁可直接用**；原子认领有现成范式（跑腿 `grab` 条件 UPDATE，先到先得，全仓无 `SKIP LOCKED`）；**presence 在线集合、审核派单逻辑、`front-review/` 独立工程均需新建**。详见 [调研正文](research/04-realtime-presence-inventory.md)。
- [07 prototype：审核中台 `front-review/` 可点原型](issues/07-prototype-review-console.md)：**已交付可运行原型**（Vue 3 + Vite，`vue-tsc` 类型检查 + 构建通过），落点定为 **`.scratch/errand-lock-review-hub/prototype/`**（非生产代码；仓库根 `front-review/` 归票 08 定）。**信息架构已成形**：左队列（多类型 tab）+ **中单条聚焦**工单（内容 / 作者 / 历史裁决 + `RejectReasonCode` 8 枚举理由 + 补充说明）+ 右状态 / 事件流三栏；**「推信号 → 拉一条 → 租约倒计时 → 审完可再拉 → 租约到点自动回收 + 离线不推/上线补推」交互全部跑通**（原型参数：租约 90s、一次一条、FIFO）。留给 05 的开放项：租约时长、一次一条 vs 预取、回收后等待累加、presence 是否按类型分流。详见 [README](prototype/README.md) 与 [截图](prototype/shot-02-busy-lease.png)。

- [05 grilling：在线审核员派单技术方案（推信号 + 拉工单）](issues/05-grill-dispatch-model.md)：presence 新建、存 **Redis**（心跳 25s / 判死 60s，仅 AUDITOR/ADMIN）；推送**复用 WS 连接层骨架、泛化新建 `/ws/review`**（粗粒度「有新工单」总信号、按类型偏好落拉取侧、上线补推）；claim 走**条件 UPDATE**（FIFO、一次一条、按类型过滤，不引 `SKIP LOCKED`）；**租约 5min、回收复用超时中心**（taskType `REVIEW_LEASE_TIMEOUT`、复合 bizId `{taskId}:{claimSeq}`、回调条件 UPDATE `CLAIMED→PENDING`，中心零改动）；池 = **全量待审**；积压告警 `review.backlog-warn-threshold`。详见 [Answer](issues/05-grill-dispatch-model.md)。

- [03 grilling：防恶意占单规则定稿](issues/03-grill-abuse-rules.md)：**不新增表 / 列**——滥用计数复用 `errand_order_event`（查 `LOCK_ABANDONED` by actor；**只计主动放弃、不计超时**，因 `LOCK_TIMEOUT` 的 `actor_id` 为 NULL 数不出来），加索引 `idx_actor_type_time(actor_id,event_type,create_time)`；**阈值：滑动 24h ≥3 次 → 冷却 2h**（全员统一、不分订单类型，**触发即重置计数窗口**，否则会放大成 ≈22h 封禁）；计数与判定走 **Redis**（ZSET `bs:errand:grab-abandon:{uid}` 精确滑动 24h + `bs:errand:grab-cooldown:{uid}` `SET NX EX 7200`；事件表仍为事实源，Redis 异常时回落 SQL），**Redis 不参与状态迁移互斥**（原子性仍靠条件 UPDATE）；**锁单期放弃不扣信用分**（确认票 01：信用分只记「承诺后违约」，滥用只记冷却，两套口径正交）；**反转票 02** 的「释放不发通知」——改为释放后给接单方发一条 `ORDER_EVENT` 结果通知（`dedupKey` 带 `lockSeq`），但仍**不做**提前预警；**不做**同单 N 次上限、发布方补偿、管理侧榜单（只留 WARN 日志）——均记为开放项。详见 [Answer](issues/03-grill-abuse-rules.md)。

- [06 grilling：审核工单数据模型与多类型统一](issues/06-grill-review-task-model.md)：裁决**新增独立 `review_task` 表**（在途工单），`content_review` 保持「只插入审计」语义（历史裁决）——本仓无迁移机制、加表≈改表零成本，且裁决（多轮，1 行/次）与工单（单生命周期，可变）是 1:N 不可共存；**多类型＝4 类**（帖/评/商/跑腿，**求购随产品整体删除、不做**），工单 `target_type` 复用 `ReviewTargetTypeVO` 可审值，详情取数＝**中台聚合直查**（延续既有 `<choose>` 直查，不为各域造 RPC）；工单态 `claim_state: PENDING→CLAIMED→DONE`（**不叫 `status`**），与 `content_review` 的裁决行**同事务**互证；**裁决强绑定**（`ReviewCase.decide` 仍是唯一内核，新增 `decideTask` 强制「先领单后裁决」，遗留 `/api/moderation/review` 收口为「反查我持有的工单」）；**`content_review` 加 `author_id` 冗余列**（对齐 `content_report` 先例）使历史裁决聚合单表化；入池＝各域进待审同事务插工单（4 触点）＋一次性回填脚本；uk `(target_type,target_id,review_round)` + 排队索引 `(claim_state,entered_at[,target_type])` + 租约索引，claim 走 PK 条件 UPDATE（不引 `SKIP LOCKED`）。详见 [Answer](issues/06-grill-review-task-model.md)。

- [08 grilling：审核端独立工程的形态与部署](issues/08-grill-review-console-deploy.md)：七问全按推荐拍板——① **同仓库新增 `front-review/`**（与 `front/` 并列、独立构建，原型 `git mv` 平移，不另开仓库）；② **替换** `/admin/review`（主工程删路由 + Profile 入口卡 + 旧视图；后端 `/api/admin/reviews/decide` 保留）；③ **新建工单 API 仍落 moderation 域**，前缀 **`/api/moderation/tasks/**`** + WS `/ws/review`，裁决复用 `ReviewCase.decide`（同事务工单 `DONE`），不新建域；④ **沿用既有 JWT + ADMIN/AUDITOR 门控**，同域共享 `localStorage` token、零 CORS，不建账号体系；⑤ **独立构建、同域挂 `/review/` 反代主后端、仅内网可达**（对齐 8083 先例），dev Vite **5174** + proxy `/api`、`/ws/review` → 8080；⑥ **不进 CI / 独立版本 / 共享类型手抄（后端枚举为事实源）/ 不引共享包**（技术债）；⑦ **仅桌面 Web**（≥1200px）。**CONTEXT 不加词条**（工程/部署非领域词）；**ADR 合并为一篇「统一审核中台」，编号归票 09（建议 0008）**。详见 [Answer](issues/08-grill-review-console-deploy.md)。

- [09 task：spec 定稿（effort 收口）](issues/09-task-write-spec.md)：**地图抵达终点**——票 01–08 决策全部按「编号更大者为准」收口进 [`spec.md`](spec.md)（`Status: accepted`）。十问逐条落稿：范围与不覆盖（含「多人单不适用」）／跑腿状态机（13 值枚举 + 完整迁移矩阵 + 零迁移脚本，改 `schema.sql`）／锁单超时（`ERRAND_LOCK_TIMEOUT`、复合 `bizId`、5min、中心零改动）／防占单（复用事件表 + Redis 计数、3 次/24h→2h 冷却、不扣分、反转票 02 发结果通知）／审核中台（`review_task` DDL + `content_review.author_id` + 推信号/拉工单派单 + `REVIEW_LEASE_TIMEOUT` 租约 + `front-review/` 工程形态 + 原型结论）／术语 12 条 ＋ 修订 2 处／**ADR 两篇 `0008`「跑腿状态机扩展」+ `0009`「统一审核中台」**／超时中心文档回填点（不改中心代码）／可观测本期与开放项／Out of scope 定稿。详见 [Answer](issues/09-task-write-spec.md)。

- **实现收口（2026-10-15）**：派生实现 effort [`errand-lock-review-hub-impl`](../errand-lock-review-hub-impl/map.md) 票 01–13 全部落地（状态机 + 锁单超时 + 防占单 + 工单池 + 派单 + `front-review/` + 旧工作台下线）；票 14 文档收口——`CONTEXT.md` 新增 12 词条、修订 2 处，`docs/adr/0008` / `0009` 落稿（不复用 0005–0007 退役号），`sql.md` 与 `schema.sql` 逐表逐列全等回写，超时中心两处文档各补两条接入方记录（`ERRAND_LOCK_TIMEOUT` / `REVIEW_LEASE_TIMEOUT`，中心零代码改动核对确认）。本 effort 至此完成。

## Not yet specified

_与 spec §9 开放项对齐（另议，不阻塞落地）。_

- **审核员工作量视图**：拉取模型的副产品（每人处理量、平均耗时、超时率）是否要做——非本期目的地，另议。
- **跑腿侧用户端 UI 细案**：草稿箱、锁单倒计时、确认/放弃的页面级设计——本期 spec 只定行为口径，视觉细案可能另开 effort。
- **管理侧「占单滥用」榜单**：触发冷却的用户量 ≈ 0 暂无读者，事实数据已在 `errand_order_event`，运营需要时可 SQL 自查。
- **草稿堆积清理**、**终态软删能力**（`CANCELLED`/`REJECTED`/`COMPLETED` 是否可软删）、**工单风险标记形态**（原型 `riskFlags` 占位，派生不落列）、**同单 N 次上限**（备选「放弃过的单本发布周期内不可再抢」）、**入池兜底对账 job**（防漏池加固）。
- **监控面板接入 / 管理接口鉴权**（超时中心 spec §9 既有开放项）。

## Out of scope

- **举报裁决 / 申诉机制**：后续 effort。
- **审核中台自建账号体系 / SSO**：本期沿用既有角色门控（ADMIN/AUDITOR），不新造登录设施。
- **超时中心旧链路下线**：属 `.scratch/timeout-center/spec.md §10` 的迁移 effort，将来一次性下线、不留双跑。
- **跑腿执行时限（送达 deadline）接入超时中心**：超时中心 spec 已明确排除；本期只接「锁单超时」「审核租约超时」两个新类型。
- **实现本身**：本 effort 只出 spec；实现已由派生 effort [`errand-lock-review-hub-impl`](../errand-lock-review-hub-impl/map.md) 票 01–13 承接完成（2026-10-15）。
- **多人单（组队）**：其在线实现已下线，本 effort 不继承其状态机、不恢复多人单（spec §1.2）。
