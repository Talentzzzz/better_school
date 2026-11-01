# 票 09 · 删拉新（营销 referral 域 + 单体侧三件套 + 表）

Type: task
Status: open
Blocked by: 08

## 上下文预算（硬约束）

| 项 | 值 |
| --- | --- |
| 目标 | **≤200k**；硬顶 **300k** |
| 预估改文件 | ~30（营销侧 ~20 + 单体侧 ~5 + 文档/词条 ~4 + legacy 表 2） |
| 必读 | 本票；营销侧 `referral` 域包；单体侧 4 个文件（见下） |
| 不读 | `sql.md` §1/§2 全篇（`sql.md` §2 本就无 referral 表，无需核对）；rafflex |
| 必跑 | `backend/marketing` 全量 + `backend/better-school` 受影响用例（`MarketingNoticeIntegrationTest`） |
| 越界信号 | 想「顺手把前端邀请码入口也删了」→ 前端归票 16（但要在 `## Answer` 列出前端残留清单） |

## 已核实事实（票面已列全，别再全仓搜）

**营销侧（`backend/marketing`，票 07 后路径）**
- api：`IReferralService`、`dto/InviteCodeResponseDTO`、`dto/InviteeResponseDTO`
- domain：`referral/**`（`IReferralRepository`、`ReferralGroupEntity`、`InviteeVO`、`ReferralGroupedMessage`、`InviteCodeGenerator`、`IReferralAccountService`、`ReferralAccountServiceImpl`）
- infrastructure：`ReferralRepository`、`IReferralDao`、`ReferralGroupPO`、`ReferralInviteCodePO`、`ReferralMapper.xml`
- trigger：`ReferralController`、`ReferralGroupedConsumer`
- types：`types/referral/**`（含 `Constants.REFERRAL_GROUPED_TOPIC` / `REFERRAL_GROUPED_CONSUMER_GROUP`）
- lottery 侧残留引用：`domain/lottery/model/valobj/ReferralGroupedMessage`（成团给双方各 +1 次抽奖机会的入口）
- 测试：`ReferralGroupIntegrationTest`、`ReferralInviteCodeIntegrationTest`、`ReferralInviteeListIntegrationTest`、`support/ReferralApiTestBase`
- 文档：`docs/api/referral.md`、`docs/db/referral.md`

**单体侧（`backend/better-school`）**
- `bs-case/src/main/java/com/betterschool/usecase/marketing/ReferralGroupedMessage.java`
- `bs-case/.../marketing/MarketingNoticeCase.java` 的 `notifyReferralGrouped`
- `bs-trigger/.../listener/ReferralGroupedConsumer.java`
- `bs-types/.../common/Constants.java` 的 `REFERRAL_GROUPED_TOPIC`
- 测试：`MarketingNoticeIntegrationTest` 的成团用例（含 `"REFERRAL_GROUPED"` 断言）

**其他**
- `backend/marketing/sql/schema-legacy.sql` 去掉 `referral_invite_code`、`referral_group`
- 根 `CONTEXT.md` 的拉新相关词条（邀请码 / 成团 / 拉新）

## Question

拉新能力整体下线：营销侧 referral 域全链删除、单体侧成团通知链路删除、表与文档清理；`REFERRAL_GROUPED` topic 全网不再有生产/消费方。

## 施工边界（不做什么）

- 前端邀请码入口归票 16（本票只在 `## Answer` 给残留清单）。
- 不动积分/抽奖/库存（票 10–14）。
- 不动 `MarketingNoticeCase` 的中奖通知与积分变动通知两条分支（票 14 才核对中奖载荷）。

## 验收

1. `backend/marketing && mvn -o test` **全量绿**。
2. `backend/better-school && mvn -o test` 全量绿（`MarketingNoticeIntegrationTest` 成团用例已删）。
3. `referral|Referral|拉新|邀请码|invite|REFERRAL_GROUPED` 在两个后端 + 根 `CONTEXT.md` 零命中（`.scratch/` 与 `sql.md` 历史文本不算）。
4. `schema-legacy.sql` 里 referral 两张表已删，且注释清单同步（若清空则删文件）。
5. `## Answer` 记录：前端残留清单（文件 + 页面入口）、删除文件计数。

## Answer

**结果：营销侧 referral 域全链 + 单体侧成团通知链路 + 表/文档全部下线，`REFERRAL_GROUPED`（`bs-referral-grouped`）全网无生产/消费方。**
marketing 全量 63 测试绿；better-school 全量绿（`MarketingNoticeIntegrationTest` 成团用例已删，残缺载荷用例改为只覆盖中奖/积分两个消费者）。

### 删除文件计数（33 个）
- 营销侧 31：`api/referral`（5）+ `domain/referral`（8）+ `infrastructure/referral`（6，含 referral 名下的 `LotteryActivityPO`——抽奖域已有自己的 `ActivityPO`/`ActivityConfigRepository`，票 07 复制后互不引用）+ `types/referral/Constants` + `types/common/ReferralGroupedMessage` + `trigger/referral`（`ReferralController`、`PingController`——points/lottery 各有自己的 Ping）+ `trigger/lottery/listener/ReferralGroupedConsumer` + `mybatis/mapper/referral/ReferralMapper.xml` + 测试 4（3 用例类 + `ReferralApiTestBase`）+ docs 2（`api/referral.md`、`db/referral.md`）
- 单体侧 2：`bs-trigger/listener/ReferralGroupedConsumer`、`bs-case/usecase/marketing/ReferralGroupedMessage`

### 关键连带（编译/行为）
- lottery 侧 `IDrawCountAccountService.addForReferralGrouped`（+impl）删除；`DrawCountReasonType` 只剩 `REDEEM`/`DRAW`；`ResponseCode` 删 4201–4206 六个码；`OutboxMessageSender` 删 `referralOutboxScan` handler；`MybatisConfig`/两个 `application*.yml` 删 referral 段；`types.common.Constants`/`types.lottery.Constants` 删 topic 与消费组常量。种子活动名「开学季拉新抽奖」→「开学季抽奖」（`LotteryCatalogIntegrationTest` 断言同步），测试库经 `scripts/test-env.sh reset-test-db` 重建。
- **运行时残留（不在仓内，需手工）**：xxl-job-admin `xxl_job_info` 里已注册的 `referralOutboxScan` 任务行会因 handler 消失而调度失败（本脚本幂等重灌不会再建出它），需在 admin 停用/删除；`outboxScan` 兜底重发不辨 topic——若库里还有 PENDING 的 `bs-referral-grouped` 旧行，会被照常投到已无人订阅的 topic（发送失败走 retry→死信，无副作用）；历史 `lottery_draw_count_lot` 若有 `reason_type='REFERRAL'` 存量行，枚举删除不影响读取（无映射路径）。
- **code-review 后收缩**：营销侧 `IUserQueryPort.getBasicBatch`（唯一调用方是已删的名单查询）随票删除——`IUserQueryPort` / `UserQueryPort` / `StubUserQueryPort` 三处同步收缩。跨工程的 `bs-api IUserQueryService.getBasicBatch` 与单体 `UserQueryDubboService` 实现因此**全网零调用方**，但属单体侧 Dubbo 契约（跨 project 改契约要重装 bs-api 并重启），超出本票边界，留给后续清理票。

### 前端残留清单（归票 16，本票未动）
- `front/vite.config.ts`：`/api/referral` 代理条目（38 行附近）+ 注释
- `front/src/api/marketing.ts`：拉新段（`getInviteCode` / `listInvitees` / `createGroup`、业务码 4201–4206 中文文案映射、`Invitee` 类型、头注释）
- `front/src/views/marketing/MarketingView.vue`：邀请码面板整块（取码/复制/分享文案「填我的邀请码…各得 1 次抽奖机会」/填码表单/邀请名单抽屉，40+ 处引用）
- 路由不动（`/marketing` 页面本身保留，只删页内邀请入口）

### 文档/词条同步
根 `CONTEXT.md` 经核对已无相关词条（零改动）；`backend/CLAUDE.md`、`backend/docs/{README,api/README,db/README,testing,architecture/marketing-services,architecture/timeout-center}.md`、`backend/marketing/docs/{README,api/lottery,db/lottery}.md`、`backend/better-school/docs/db/{README,chat}.md` 全部改为两域口径；`schema-legacy.sql` 头注释清单、`schema.sql`、`init-dev.sql`、`xxl-job-tasks.sql` 同步（referral 两表已删，文件未清空故保留）。

<!-- 收口时补：Context used: ~95k；未触发拆分；连带影响清单见上 -->
