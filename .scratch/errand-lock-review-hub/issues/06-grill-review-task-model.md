# 06 grilling：审核工单数据模型与多类型统一

Type: grilling
Status: resolved
Blocked by: 05

## Question

审核中台要「同屏审多种内容」（帖子/评论/商品/求购/跑腿），而派单（票 05）需要**指派、租约、状态**这些当前 `content_review` 未必有的字段。要裁决数据模型：

1. **复用还是新增**：现有 `content_review`（见 `backend/sql/init.sql`，`ReviewCase.java`、`RejectReasonCode` 等）承载「审核结论 + 理由」，是**历史裁决表**；派单需要的是**在途工单**（谁在看、租约到没到、是否已领走）。方案：(a) 给 `content_review` 加 `assigned_to / lease_expire_at / claim_status` 列；(b) 新增独立 `review_task` 表（在途工单），裁决后写回 `content_review`（保留其历史语义）；(c) 其他。给出取舍（迁移成本、查询复杂度）。
2. **多类型怎么统一**：`content_review` 已有 `target_type`（帖子/评论/商品/求购/跑腿）一类设计，确认口径；各类型**展示素材**差异巨大（帖子有图文、评论有楼中楼、跑腿有配图+悬赏），工单详情怎么取（各域自建 `detail` 接口，还是中台聚合调用）。
3. **工单状态机**：待审 → 已指派（租约中）→ 已裁决 / 已过期回收；与 `content_review` 的 `PENDING/PASS/REJECT` 口径对齐；「先审后发」对**作者本人可见**（现有语义）在工单层怎么体现。
4. **裁决写入**：沿用 `ReviewCase.decide(reasonCode, supplement)`？多类型下 `decide` 的分发（`target_type` → 各域用例）；裁决后回写工单状态与 `content_review` 的原子性（同事务）。
5. **索引与查询**：派单要 `(status, type, created_at)` 之类的排队索引；`SELECT ... FOR UPDATE SKIP LOCKED` 需要什么索引配合（对齐票 05 的 claim 口径）。
6. **迁移**：现有 `content_review` 存量数据要不要回填成工单；增量脚本编号。
7. **历史裁决展示**：审核员看单时要看「该作者历史裁决」——数据从哪来（`content_review` 按作者聚合）。

**产出**：定稿的数据模型（DDL 级别）+ 多类型统一口径 + 裁决写入路径，写进 `spec.md`。

## Answer

**resolved（2026-10-14，grilling 一轮）。** 下列为定稿；编号更大的票若反转，以后票为准。产出＝**设计定稿，不含实现**（实现由后续拆票承接；`reconcile`/收口见票 09）。

### 0. 开工前事实校正（改变了票内 1/2/6 三问的框架）

- **`content_review` 是「只插入」审计表**：`uk_target_action_round(target_type,target_id,action,review_round)`，字段仅 `target_type/target_id/review_round/action/source/operator_id/reason/reason_code/create_time/update_time`——**无 `author_id`、无任何号主/租约列**；`docs/db/moderation.md:27` 明写「只读审计：审核队列不查此表」。它承载的是**历史裁决**。
- **当前审核队列不是表**：`IModerationDao.selectReviewQueue` / `selectReviewDetail`（`bs-app/src/main/resources/mybatis/mapper/ModerationMapper.xml`）用 `<choose>` 按 `targetType` **分别直查 4 张业务表**（`post` / `post_comment` / `product` / `errand_order`，`WHERE state='PENDING_REVIEW'`）。队列是**算出来的**，从无持久化。⇒ `review_task` 是**纯新建物**，不是「改造既有队列」。
- **「求购(WANTED)」在后端不存在**：全库无求购表，`ReviewTargetTypeVO` 仅 `POST/COMMENT/ERRAND/PRODUCT`（另有举报专用 `USER/MESSAGE`）；`front/src/types/api.ts:235` 亦无。用户口述：**求购已整体删除，本 effort 不做**。⇒ 可审类型 = **4 类**（帖/评/商/跑腿），工单 `target_type` 直接复用该枚举的可审值。
- **无增量迁移机制**：`sql/migrations/` 已随票 05 全库重写删除，`backend/better-school/sql/schema.sql` 是**唯一 DDL 来源**，改表＝改 `schema.sql` + 重建库（票 01 已确认，历史最大编号 028）。⇒ 新增表与给旧表加列**同为零迁移成本**，不构成选型理由。

### 1. 结论（逐问）

| # | 问题 | 裁决 |
|---|---|---|
| 1 | 复用 vs 新增 | **(b) 新增独立 `review_task` 表**（在途工单）；`content_review` **语义一字不动**（仍是只插入的裁决记录；仅加一冗余列 `author_id`，见 §8）。见 §2 |
| 2 | 多类型统一 | 工单 `target_type` 复用 `ReviewTargetTypeVO` 的 **4 个可审值**（排除 `USER`/`MESSAGE`）；**求购不做**。详情取数＝**中台聚合直查**（延续现有 `<choose>` 直查形态：单体同库，不为各域造 RPC）；素材差异用「通用列 + 类型专属上下文」表达。见 §3 |
| 3 | 工单状态机 | `claim_state`：**PENDING(在池) → CLAIMED(租约中) → DONE(已裁决)**，回收 `CLAIMED→PENDING`；字段名用 `claim_state`（**不叫 `status`**，业务表已占 `state`）。与 `content_review` 的 `PENDING/PASS/REJECT` 对齐：**PENDING ⇔ 业务行 `state=PENDING_REVIEW` 且本轮无裁决行；DONE ⇔ 本轮已落 `APPROVE/REJECT`**。「先审后发作者本人可见」是**业务域既有语义**（业务表 `state=PENDING_REVIEW` 时作者可见），**工单层不承载可见性**——开单时机＝业务行进入 `PENDING_REVIEW`。见 §4 |
| 4 | 裁决写入 | 沿用 `ReviewCase.decide(...)` 作**唯一裁决入口**（多类型 `switch` 分发既有）；新增**工单感知包装** `decideTask(taskId,...)`，**强绑定**：裁决必须持有该工单（`claim_by=操作人 且 claim_state=CLAIMED`），**同事务**关单。原子性＝单体同库同事务（域状态流转 + `content_review` insert + 通知 + 关单）。见 §5 |
| 5 | 索引与查询 | uk `(target_type,target_id,review_round)`（同轮只开一张，`INSERT IGNORE` 幂等入池）；排队索引 `(claim_state,entered_at)`＋`(claim_state,target_type,entered_at)`；租约索引 `(claim_state,lease_expire_at)`。claim 走 **PK**（`WHERE id=? AND claim_state='PENDING'`），**不引 `SKIP LOCKED`**（对齐票 05）。见 §6 |
| 6 | 迁移 | **不把 `content_review` 存量回填成工单**（历史裁决＝已完成，不该入池）；池来源＝业务表 `state='PENDING_REVIEW'` 行 ⇒ **入池点＝各域进入待审的同事务**（4 处「业务写时插工单」）＋**一次性回填脚本**兜存量。本仓无存量 ⇒ 回填脚本可选。无编号脚本；若要编号从 **028** 续。见 §7 |
| 7 | 历史裁决展示 | `content_review` **加冗余列 `author_id`**（对齐 `content_report` 已有 `author_id` 的先例）；历史聚合变**单表查询**。工单详情所需 `author_id` 冗余在 `review_task`。见 §8 |

### 2. 复用 vs 新增：为什么是独立表（票内 1）

**裁决：新增 `review_task`（在途工单），与 `content_review`（历史裁决）严格分离。**

理由（对票面 (a)/(b) 的取舍）：

- **语义不可共存**：`content_review` 是「一次裁决＝一行」的**只插入**审计，靠 `uk_target_action_round` 做重放幂等；而工单是**有生命周期的可变行**（领单改 `claim_by`/`claim_state`、回收改回、租约到期刷新）。给审计表加 `assigned_to/lease_expire_at/claim_status` 会同时破坏（i）「只插入」不变量、（ii）uk 的「重放即跳过」语义——**同一 (target,round) 的裁决行唯一，但工单可被反复领走/回收**，两者是 1:N，硬塞一张表只能给 uk 加更多维度或放任重复行。
- **迁移成本不构成理由**：本仓改表＝改 `schema.sql` + 重建库（§0），**加新表与加列等价为零成本**，故不存在「复用省迁移」的收益。
- **查询复杂度**：在途工单要**排队索引 + 条件 UPDATE 认领 + 租约字段 + 回收计数**，天然属独立表；审计表保持**窄表**、只按 (作者/目标) 聚合历史。分表后两侧查询都更简单，而非更复杂。
- **(c) 其他**：不取。既无必要把两者合并，也无需第三个「队列快照」表——池就是 `review_task` 本身（`claim_state='PENDING'` 全量）。

### 3. 多类型统一（票内 2）

- **类型口径**：`review_task.target_type` = `ReviewTargetTypeVO` 的 **POST/COMMENT/PRODUCT/ERRAND**（`USER`/`MESSAGE` 仅举报用，不入池）。**求购(WANTED) 本期及产品内均不存在**——原型/`map.md` 里的「五类」为过时愿景，随本票清理（见 §11）。未来若建求购域，**加一个枚举值 + 一条 detail 分支即可**，DDL 与设计不返工。
- **详情取数＝中台聚合直查**：延续现有 `selectReviewDetail` 的形态——moderation 域在**同库**内按 `target_type` 分派到 `post/post_comment/product/errand_order` 直查（`LEFT JOIN bs_user` 取作者昵称）。**不为各域造 `detail` RPC**：单体同库，直查免去跨域接口与版本耦合；代价是每新增类型要加一条 `choose` 分支（可接受，且与现状一致）。
- **素材差异的表达**：工单/详情用**「通用字段 + 类型专属上下文」**，不强行同构——
  - 通用：`content`（正文/标题+描述）、`images`（JSON 数组）、`authorId/authorNickname`、`entered_at`、`reviewRound`；
  - 类型专属：`reward`（跑腿悬赏，商品复用为 price，原型口径）、评论的**上下文**（父帖标题 / 被回复者）。类型专属字段**不进 `review_task` 列**（工单只存定位与派单态，内容素材实时直查），避免列爆炸。
- **`source`（入池来源）** 沿用 `ReviewSourceVO` 的 `MANUAL/KEYWORD/RANDOM` 扩展点（常规待审 / 敏感词命中转审 / 抽检），默认 `MANUAL`；本期只写 `MANUAL`。

### 4. 工单状态机（票内 3）

```
             claim（CAS 条件 UPDATE，claim_seq+1，租约 5min）
  PENDING ───────────────────────────────────────────────► CLAIMED
     ▲                                                        │
     │  回收（租约超时回调 / 主动放回；recycle_count+1）          │ decide（同事务）
     └────────────────────────────────────────────────────────┤
                                                              ▼
                                                            DONE（终态，无出边）
```

- **状态集合 `claim_state`**：`PENDING`（在池、无人持）· `CLAIMED`（已领、租约中）· `DONE`（已裁决）。**只有一个终态 `DONE`**——裁决结论（`APPROVE/REJECT`）落在 `content_review`，工单不必再分「通过态/拒绝态」。
- **与 `content_review` 口径对齐**：
  - `PENDING` ⇔ 业务行 `state='PENDING_REVIEW'` **且** 本 `review_round` 尚无 `content_review` 行；
  - `DONE` ⇔ 本 `review_round` **已落** `APPROVE`/`REJECT` 行（**同事务**，见 §5）——即 `claim_state='DONE'` 与 `uk_target_action_round` 命中**互为充分**；
  - 「`PENDING/PASS/REJECT`」是**裁决侧**动作枚举（`ReviewActionVO`），**不是**工单态；两者不混用命名。
- **`recycle_count` 与升级**：每次回收 `+1`；`≥3` 置 `escalated=1` 并告警（同一条反复领走又超时不再无声循环，对齐票 05）。
- **「先审后发」作者本人可见**：不体现在工单层。可见性由**业务域**决定（`post/post_comment/product` 的 `state=PENDING_REVIEW` 时作者可见、对外不可见；跑腿 `PENDING_REVIEW` 作者可见），`review_task` **只是派单凭据**；`DONE` 只表示「已裁决」，对外可见与否由业务行流转（`approvePost` 等）决定。**工单不引入任何新的可见性规则**。

### 5. 裁决写入路径（票内 4）——强绑定

**唯一裁决内核沿用** `ReviewCase.decide(targetType,targetId,approve,reasonCode,supplement,operatorId)`（多类型 `switch` 分发到各域 `approveXxx/rejectXxx` + `recordReview` + 通知，**逻辑不变**）。新增**工单感知**入口，强制「先领单后裁决」：

```
decideTask(taskId, approve, reasonCode, supplement, operatorId):
  ① 载入 task；断言 task.claim_by == operatorId 且 claim_state == 'CLAIMED'
       —— 不满足 → 抛错（403/409「请先领单 / 工单已被回收」）；CANCELLED/DONE 另按幂等分支（见下）
  ② 分发：switch(task.targetType) → 复用既有 approve()/reject() 分支
       （域状态流转条件 UPDATE + content_review INSERT IGNORE + 跨域资金动作（跑腿退款）+ 通知）
  ③ 关单（同事务）：
       UPDATE review_task
          SET claim_state='DONE', decided_at=NOW(), update_time=NOW()
        WHERE id=? AND claim_state='CLAIMED' AND claim_by=?       -- 持单人守卫
```

- **原子性**：单体同库，①–③ 在**同一 Spring 事务**；域状态流转与 `content_review` 落库已是同事务现状，关单并入即得「业务行已切 + 裁决已记 + 工单已关」三者的原子。
- **幂等（重试语义）**：重放时 ② 的域方法命中 0 行（终态幂等静默）、`recordReview` 撞 uk 跳过、③ 命中 0 行（已 `DONE`）——**整体幂等成功、零副作用**（同既有 `handlePayTimeout` 口径）。⇒ 重试不应报错：`claim_state='DONE'` **且** `targetType/targetId/review_round` 一致时，按幂等成功处理。
- **遗留入口收口（强绑定的代价）**：现 `POST /api/moderation/review`（`ModerationController.review` → `ReviewCase.review`）**不保留绕过路径**——改为**按 `(targetType,targetId,当前登录用户)` 反查其持有的在途工单**（`claim_by=我 且 claim_state='CLAIMED'`），命中则转 `decideTask`，未命中 → 409「请先领单」。前端工作台（`front/ReviewWorkbenchView.vue`）随之改走「领单→裁决」。
- **多类型分发的落点**：仍是 `target_type → 各域用例`（现有 `switch`），本票不新增分发维度；`review_task` 只提供「定位 + 派单态」，**不参与业务裁决语义**。

### 6. 索引与查询（票内 5）

- **uk `(target_type, target_id, review_round)`**：同一轮的同一内容**只允许一张在途工单**；入池走 `INSERT IGNORE`，撞键＝已入池（幂等，对齐 `content_review` 的 uk 手法）。
- **排队索引 `(claim_state, entered_at)`**：claim 的候选选择 `WHERE claim_state='PENDING' [AND target_type=?] ORDER BY entered_at ASC LIMIT 1`——**FIFO（最早入队优先）**；类型只作**过滤**、不做优先级插队（对齐票 05「不做抢审」）。
- **`(claim_state, target_type, entered_at)`**：带类型过滤的候选选择覆盖索引（类型偏好落拉取侧，票 05）。
- **`(claim_state, lease_expire_at)`**：租约**惰性兜底回收**（claim 前顺带 `UPDATE ... WHERE claim_state='CLAIMED' AND lease_expire_at < NOW()`）与运维扫描。
- **claim 的条件 UPDATE 走 PK**：`WHERE id=? AND claim_state='PENDING'`——主键命中，**无需额外索引**；「影响行数=1 者赢」即原子指派，**不引 `SELECT ... FOR UPDATE SKIP LOCKED`**（全仓无先例，单库 + 条件 UPDATE 已足够，票 05 已定）。
- **`entered_at` 而非 `create_time` 作排序键**：回收不改 `entered_at`（等待时长继续累加，对齐原型结论「回收后保留原入队时间」）；取值＝**「业务行进入待审的时刻」**（真实等待时长从进入待审起算，而非插工单时刻）——⚠️ **注意各表字段不齐**：`post`/`product` 有 `review_entered_at` 列可直接取；**`post_comment`/`errand_order` 无此列**，分别取 `create_time`（评论发布即待审）与**支付成功时刻**（跑腿转 `PENDING_REVIEW` 的 `update_time`／`errand_order_event` 的 `PAID` 行）。⇒ 票 05 的 `ORDER BY created_at` 由 **`entered_at`** 落定（同义收紧）。
- **租约复合 bizId 与回调守卫**：`claim_seq` 每次领单 `+1`，作超时中心 `bizId={taskId}:{claimSeq}` 的轮次与回调守卫的比对值（同票 02 的 `lock_seq` 手法，**中心零代码改动**；注册/回调细节由票 05 落 spec）。

### 7. 迁移与入池（票内 6）

- **存量 `content_review` 不回填成工单**：它是**历史裁决**，已完成、不该进池。**只加列 `author_id`**（见 §8），无数据搬迁。
- **池的来源＝业务表待审行** ⇒ 两条入池通道：
  1. **入池点（增量）**：各域**进入 `PENDING_REVIEW` 的同事务**插一张 `review_task`（`INSERT IGNORE`）。四个触点：
     - `post`：直接发布 / 草稿提交进待审；
     - `product`：提交 / 草稿提交 / 在售改内容回审 / 重新上架（`review_round+1` 时**开新一轮工单**）；
     - `post_comment`：发布进待审；
     - `errand_order`：**支付成功**（`PENDING_PAYMENT→PENDING_REVIEW`）。
     `entered_at` 取「业务行进入待审时刻」（POST/PRODUCT 取 `review_entered_at`；COMMENT 取 `create_time`；ERRAND 取支付成功时刻，见 §6），`review_round` 取业务行当前轮次。
  2. **回填（存量，一次性）**：`INSERT IGNORE INTO review_task(...) SELECT ... FROM post/post_comment/product/errand_order WHERE state='PENDING_REVIEW'`（真实部署用；**本仓无存量 ⇒ 可选**，spec 附脚本）。
  3. **兜底（可选，防漏池）**：入池点靠「各域同事务插单」，理论上漏插＝内容卡在待审无人可见。留一条**惰性自愈**（claim/队列读取时对「业务行 `PENDING_REVIEW` 但无在途工单」的行补插 `INSERT IGNORE`，或挂一个低频 xxl-job 对账）——**本期列为可选**，spec 记为加固项，不阻塞主链路。
- **队列读取口径切换**：现有 `GET /api/moderation/queue`（直查业务表）改为**读 `review_task`**（`claim_state='PENDING'` + 类型过滤 + FIFO）；`ReviewQueueItemEntity` 的取数改为「先按工单定位，再按 `target_type` 直查业务表取素材」。
- **无编号迁移脚本**：`schema.sql` 改表 + 重建库生效；若要恢复编号机制，从历史最大 **028** 续号（票 01 同结论）。
- **`review_round` 语义对齐**：`review_task.review_round` 与 `content_review.review_round` **同源**（取业务表 `review_round`；COMMENT/ERRAND 恒为 1）。⇒ 「同一内容重提」＝新轮次＝**新工单行**（旧工单停在 `DONE`），历史裁决天然按轮次可溯。

### 8. 历史裁决展示（票内 7）

- **数据源＝`content_review`，但需补 `author_id`**：现表**无 `author_id`**，按作者聚合得反查 4 张业务表。裁决＝**给 `content_review` 加冗余列 `author_id BIGINT NOT NULL`**（对齐 `content_report.author_id` 的先例；只加列、**不改只插入语义**）。写入路径 `recordReview(...)` 增传 `authorId`（`ReviewQueueItemEntity.getAuthorId()` 已有，改动小）。
- **两类历史查询都单表化**：
  - **该作者历史裁决**：`SELECT ... FROM content_review WHERE author_id=? ORDER BY id DESC`（审核员看单时的「该作者历史」）；
  - **同一内容历轮**：`WHERE target_type=? AND target_id=? ORDER BY review_round DESC, id DESC`（看「改后重审」的完整脉络）。
- **工单详情免 join**：`review_task.author_id` 冗余存一份，工单详情直接带出作者，历史裁决再按 `author_id` 单表拉。
- **风险标记（原型 `riskFlags` 占位）**：**不落列、派生**——如「该作者/该内容有 `content_report` 命中」「同作者近期多次被拒」。本期**列为开放项**（形态待定，可先不做），不阻塞数据模型。

### 9. DDL 定稿（供票 09 原样落 `schema.sql`）

```sql
-- 新增：审核工单（在途派单凭据；与 content_review 的历史裁决语义严格分离）
CREATE TABLE IF NOT EXISTS review_task (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    target_type     VARCHAR(20)  NOT NULL COMMENT '内容类型 POST/COMMENT/PRODUCT/ERRAND（ReviewTargetTypeVO 可审值）',
    target_id       BIGINT       NOT NULL COMMENT '内容业务 ID（post_id/comment_id/product_id/errand_order_id）',
    review_round    INT          NOT NULL DEFAULT 1 COMMENT '审核轮次，与 content_review.review_round 同源（COMMENT/ERRAND 恒 1）',
    author_id       BIGINT       NOT NULL COMMENT '作者用户 ID（冗余，工单详情 / 历史聚合免 join）',
    source          VARCHAR(20)  NOT NULL DEFAULT 'MANUAL' COMMENT '入池来源 MANUAL/KEYWORD/RANDOM（ReviewSourceVO）',
    claim_state     VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '工单态 PENDING(在池)/CLAIMED(租约中)/DONE(已裁决)；勿与业务表 state 混用',
    claim_by        BIGINT       NULL COMMENT '当前持单人（审核员用户 ID）',
    claim_seq       INT          NOT NULL DEFAULT 0 COMMENT '领单轮次（每次 claim +1；租约复合 bizId {id}:{claimSeq} 与回调守卫）',
    lease_expire_at DATETIME     NULL COMMENT '租约到期时刻（claim 时 = NOW()+5min）',
    recycle_count   INT          NOT NULL DEFAULT 0 COMMENT '回收次数（≥3 置 escalated + 告警）',
    escalated       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '需人工干预标记（recycle_count≥3）',
    entered_at      DATETIME     NOT NULL COMMENT '进入待审时刻（FIFO 排序 / 等待时长锚点；POST/PRODUCT 取 review_entered_at，COMMENT 取 create_time，ERRAND 取支付时刻；回收不改）',
    decided_at      DATETIME     NULL COMMENT '裁决时刻（DONE 时写）',
    create_time     DATETIME     NOT NULL COMMENT '创建时间',
    update_time     DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_target_round (target_type, target_id, review_round) COMMENT '同一轮同一内容只开一张在途工单（INSERT IGNORE 幂等入池）',
    KEY idx_queue (claim_state, entered_at) COMMENT 'FIFO 取候选（跨类型）',
    KEY idx_queue_type (claim_state, target_type, entered_at) COMMENT 'FIFO 取候选（按类型过滤）',
    KEY idx_lease (claim_state, lease_expire_at) COMMENT '租约惰性/扫描回收'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审核工单（在途派单；不承载裁决与可见性）';
```

```sql
-- 改：content_review 加冗余作者列（供按作者聚合历史裁决；语义仍为「只插入审计」）
ALTER TABLE content_review
    ADD COLUMN author_id BIGINT NOT NULL COMMENT '作者用户 ID（冗余，供按作者聚合历史裁决；对齐 content_report.author_id）' AFTER target_id;
```

> ⚠️ **真实部署注意**：若目标库 `content_review` 已有历史行，`ADD COLUMN ... NOT NULL` 在 MySQL 严格模式下会失败/落 0。稳妥法：先加 `NULL` 列 → 按 `target_type/target_id` 反查业务表回填 → 再改 `NOT NULL`。**本仓无存量 ⇒ 直接 `NOT NULL` 即可**。

**claim 的 CAS（承接票 05，写进 spec）**

```sql
-- ① 选候选（FIFO，可带类型过滤）
SELECT id FROM review_task
 WHERE claim_state='PENDING' [AND target_type=?]
 ORDER BY entered_at ASC LIMIT 1;
-- ② 认领（影响行数=1 者赢）
UPDATE review_task
   SET claim_by=?, claim_state='CLAIMED', claim_seq=claim_seq+1,
       lease_expire_at=NOW()+INTERVAL 5 MINUTE, update_time=NOW()
 WHERE id=? AND claim_state='PENDING';
-- 同事务写 timeout_register_outbox：task_type='REVIEW_LEASE_TIMEOUT', biz_id='{id}:{claim_seq}', action_time=now+5min
```

**回收（承接票 05，超时中心回调 / 主动放回 / 惰性兜底）**

```sql
UPDATE review_task
   SET claim_state='PENDING', claim_by=NULL, lease_expire_at=NULL,
       recycle_count=recycle_count+1, update_time=NOW()
 WHERE id=? AND claim_state='CLAIMED' AND claim_seq=?;   -- 轮次守卫；0 行 = 已裁决/已放回/已按新轮次重领 → 幂等成功
```

### 10. 术语（供票 09 落 `CONTEXT.md`）

沿用票 05 已拟名称，本票确认落册：**审核工单**（在途派单凭据，区别于 `content_review` 即**历史裁决**）· **派单信号**（推送，只唤醒）· **领单**（claim，原子指派）· **租约**· **放回**· **回收**。建议新增一条**辨析词条**：`审核工单` 的 `_Avoid_: 审核记录、审核队列（队列是取候选的视图，工单是持久化实体）`。

### 11. 随票完成的清理（求购连带）

用户确认「**求购已整体删除**」⇒ 对齐本 effort 内的过时残留（日期可考的调研/归档记录不动）：
- `map.md`：目的地/Notes/图绘期决策里的「帖子/评论/商品/求购/跑腿」→ **4 类**（帖/评/商/跑腿）＋ 新增本票决策索引行。
- 票 07（原型）与 `prototype/README.md`、`prototype/src/{types/review.ts, mock/tasks.ts, stores/console.ts, components/TaskWorkbench.vue}`：去掉 `WANTED` 类型、求购 mock、`budget` 字段与其展示（原型重建验证）。
- `issues/04-*`、`research/04-*` 保留原样：它们是**当时**的调研记录（彼时 `.scratch` 目标确含求购），属归档，不改写历史事实。
- **顺手修一处仓库卫生**：`prototype/dist/` 与 `prototype/*.tsbuildinfo` 是**构建产物**，`.gitignore` 明确忽略（`dist` / `*.tsbuildinfo`），却被历史提交（`ff8b02c`）误纳入跟踪——其中 `dist` 里编译后的 JS **本就含 `求购` 字面量**（源码已清而产物未清）。本次 `git rm --cached` 去掉跟踪（文件仍在盘上，`npm run build` 可再生），使仓库与 `.gitignore` 一致。

### 转票锚点

- **票 05（派单）**：本票定 `review_task` DDL 与 `entered_at` 排序键、`claim_seq` 轮次（对接 `REVIEW_LEASE_TIMEOUT` 复合 bizId）；票 05 的 `claim_state`/`claim_by`/`claim_seq`/`lease_expire_at`/`recycle_count`/`source` 字段名与本票一致。
- **票 08（工程形态）**：新增「工单感知」的领单/详情/裁决接口归属与前端改造（`front-review/`）。
- **票 09（spec）**：本 Answer 原样收口进 `spec.md` §统一审核中台（数据模型 / 状态机 / 裁决路径 / 索引 / 迁移 / 术语）。
- **拆票（实现）**：入池点 4 处、`review_task` 领域/仓储/DAO、claim/回收、`decideTask` 与遗留入口收口、`content_review.author_id`、前端工作台改造。
