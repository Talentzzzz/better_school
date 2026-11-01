# 06: 拉新服务

**Spec:** `.scratch/marketing/spec.md` §「营销域」→「数据模型与行级时序」第 1 条「填码成团」「接口口径与失败语义」+ `.scratch/marketing/issues/01-baseline-decisions.md`、`05-marketing-data-model.md`

**What to build:** 拉新服务的完整可跑链路：一人一码的邀请码懒生成、填码成团（含「不是自己」「活动开始后注册」「一生只能被邀请一次」三条硬约束）、已拉新名单、成团事件发出。

**注意反转**：用户 2026-09-14 拍板「**成团只加抽奖次数**」——**不建**成团送积分的配置表，事件体**不带** `bonusPoints`。

**Blocked by:** 01

**Status:** resolved

- [x] schema `bs_referral`：`referral_invite_code` + `referral_group` + 本服务自己的 `outbox_message`；迁移 `024_referral.sql`
- [x] 邀请码：一人一码、**懒生成**（首次取码时才生成）、8 位、**去易混字符**；`uk_user` 保证幂等可重放
- [x] `referral_group` **只 `uk(invitee_id)`**——它已蕴含「同一对只一个团」；团恒 2 人
- [x] **不建**成团送积分配置（`referral_config` / `REFERRAL_BONUS` 均不落）
- [x] `GET /api/referral/invite-code` → `{ code }`
- [x] `GET /api/referral/invitees` → `PageResult<{userId,nickname,avatar,groupedAt}>`；`total` = 已拉新人数、前 8 条供头像并排；`created_at DESC`
- [x] `POST /api/referral/groups` body `{code}`，时序照 spec 第 1 条：查码 → 校验「不是自己」→ **事务外** Dubbo 取被邀请人 `createdAt` 校验「活动开始后注册」→ 事务内 `INSERT referral_group`（撞 `uk_invitee` = 已被邀请过）+ 写 outbox → 提交
- [x] **「一人一次」由唯一键兜，不靠先查后写**
- [x] 昵称头像一律**经 Dubbo（`IUserQueryPort`）取回后应用层拼装**，不反查生产方、不存快照
- [x] 发 `bs-referral-grouped` `{groupId, inviterId, inviteeId}`（**不带 `bonusPoints`**、不带昵称头像），走 outbox
- [x] 失败码 + 逐条固定中文文案：`CODE_NOT_FOUND` / `ACTIVITY_NOT_STARTED` / `ACTIVITY_ENDED` / `OLD_USER` / `ALREADY_INVITED` / `CANNOT_INVITE_SELF`；成功码一律 `"200"`
- [x] 活动 `end_at` 之后填码一律拒（`ACTIVITY_ENDED`）；`enabled = 0` 同理
- [x] **活动起止的取法（2026-09-15 用户拍板）**：拉新**直读 `bs_lottery.lottery_activity`** 这一单行只读配置（拿 `start_at` / `end_at` / `enabled`），本服务 schema **不自带**活动配置表。**⚠️ 这是对 spec「三个独立 schema、跨库不 join、互相只存 id」硬约定的有意偏离**（spec 未定义拉新从哪取活动起止，属缺口；三个 schema 本就在同一 MySQL 实例）。**限定**：只读该单行配置，**不 join 任何业务表、不写**；`bs_user.created_at` 仍走 Dubbo（`IUserQueryPort`），别顺手也改成直读单体库
- [x] 验收：两个用户走通「A 取码 → B 填码成团」；A 拉新名单出现 B；重复填码 / 填自己的码 / 老用户 / 活动未开始各自返回对应业务码与文案

## 实现要点（票 06）

### 落了什么

`backend/referral/` 从「只有骨架」到「能跑完一整条拉新链路」，外加一处共享设施改造：

| 面 | 落点 |
| --- | --- |
| SQL | `sql/schema-referral.sql`（DDL 主体）、`sql/migrations/024_referral.sql`；**另提前落** `sql/schema-lottery.sql` + `sql/migrations/023_lottery.sql`（只有 `lottery_activity` 一张表 + 种子，见下） |
| 共享设施改造 | `sql/init-marketing-{dev,test}.sql` 改成**只建三个库、不切库**；`scripts/test-env.sh` 的 `apply_marketing_init` 改三参 `<建库文件> <库名> <DDL文件>`，`USE` 由脚本插在中间 |
| types | `ResponseCode` 六个 4xxx 业务码（info 逐条固定）、`OutboxStatus`、`Constants`（topic / 邀请码长度 / 字符集），**删掉骨架里没有用途的 `REFERRAL_KEY_PREFIX`**（本服务不用 Redis） |
| api | `IReferralService` + 4 个 DTO + `PageResult`；`referral-api` 补 `jakarta.validation-api`（请求 DTO 的 `@NotBlank @Size`） |
| domain | `ReferralGroupEntity`、`InviteeVO` / `ActivityConfigVO` / `ReferralGroupedMessage`、`IReferralRepository` / `IActivityConfigRepository`、`InviteCodeGenerator`、`IReferralAccountService` + Impl（时序与事务边界在这里） |
| infrastructure | `IReferralDao` + 3 个 PO + `ReferralMapper.xml`；`ReferralRepository` / `ActivityConfigRepository`；outbox 三件套（**逐字照抄单体、只换包名**） |
| trigger | `ReferralController implements IReferralService`（照 `backend/CLAUDE.md` §5，与积分服务同法） |
| app | datasource（`bs_referral`）、mybatis、rocketmq producer、`referral.activity-config-table`、`MybatisConfig` / `TransactionConfig` |
| 测试 | `referral-app/src/test`：`ReferralApiTestBase` + `StubUserQueryPort` + 3 个测试类共 **23 个用例** |
| 文档 | `docs/api/referral.md`、`docs/db/referral.md`、`docs/{api,db}/README.md`、`docs/testing.md`、`docs/architecture/marketing-services.md` |

### 落地时定的三件事（票面没写死，已回填票面与文档）

1. **活动配置表由本票提前落**（`sql/schema-lottery.sql` + `sql/migrations/023_lottery.sql`）：
   票面只说「拉新直读 `bs_lottery.lottery_activity`」，但那张表归票 07 建 ⇒ 本票的链路与集成测试都没得读。
   折中：**只落 `lottery_activity` 一张表 + 种子**（按票 05 设计票 §2② 的完整列），
   **票 07 往 `schema-lottery.sql` 与 `023_lottery.sql` 两个文件末尾追加**其余表与奖品种子、**不要重建**。
   ⚠️ 用 `023`（而不是让它塞进 `024_referral.sql`）：迁移号按服务归属，票 07 的增量才落在自己的号段上。

2. **跨库读的表名走配置**：`referral.activity-config-table`（开发 `bs_lottery.lottery_activity` /
   测试 `bs_lottery_test.lottery_activity`）。写死会让集成测试连着 `bs_referral_test` 去读**开发库**的活动窗口——
   测试就再也不受用例里 `UPDATE` 的影响，三个活动分支全是假绿。

3. **建库口径改了**：`init-marketing-*.sql` 只建三个库（无 `USE`），`USE` 由 `apply_marketing_init` 插在
   建库文件与 DDL 主体之间。这样三份 `schema-<svc>.sql` 都不含库名、开发库与测试库才能共用一份 DDL。
   手工等价命令见 `docker-compose.yml` 头注释 1 与 `docs/architecture/marketing-services.md` §6。

### 实现细节里值得记的

- **邀请码懒生成的「撞键二选一」**：`referral_invite_code` 有 `uk_user` 与 `uk_code` 两个唯一键，插入失败时
  分不清撞了哪个 ⇒ 按 `user_id` 回查一次即可：查到了是「本人早有码」（正常重放路径），没查到是撞码、换一串重掷（上限 5 次）。
- **不用 `INSERT IGNORE`，改「普通 INSERT + 只捕 `DuplicateKeyException`」**（code-review 采纳）：
  `INSERT IGNORE` 会把「字段超长 / 非空约束」这类**真故障**一并吞成「撞键」，结果就是返回一串根本没落库的码。
  撞键与写失败必须分得开：前者是幂等命中、后者照常抛出去报 999。翻译在仓储做，域层不认识 Spring 的 DAO 异常。
- **活动闸门在查码之前**：`enabled=0` / 未开始 / 已结束三种拒绝都发生在查码之前。
  spec §⑥ 的口径是「活动 `end_at` 之后填码**一律拒**」，且读单行配置比查码便宜。
  副作用：活动未开始时填一个不存在的码回的是 `ACTIVITY_NOT_STARTED` 而不是 `CODE_NOT_FOUND`（已在 referral.md 写明步骤顺序）。
- **老用户判据「取不到注册时间也按老用户拒」**：Dubbo 查不到人时不能静默放行，
  否则一个脏 id 就能拿到抽奖次数。宁可少发、不可发错。
- **邀请码比对大小写与空格不敏感**（`trim + toUpperCase(Locale.ROOT)`）：手抄的码不该因为多敲一个空格报「不存在」。
- **页码夹上限 `MAX_PAGE = 10000`**（code-review 采纳）：`(page - 1) * pageSize` 是 int 运算，
  `page=Integer.MAX_VALUE` 会溢出成负 OFFSET、报 999。有一个用例钉住这个行为。

### 验证怎么做的

`cd backend/referral && mvn -o test -Dtest='Referral*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false` → **23 个用例全绿**：

| 用例 | 覆盖 |
| --- | --- |
| `ReferralInviteCodeIntegrationTest`（5） | 首次取码 8 位 + 字符集内 + 落行、重复取码同一串且不新增行、两人码不同、未登录 401、探活经同步边取回当前用户 |
| `ReferralGroupIntegrationTest`（11） | 主干（团落一行 + `groupId` 回填 + 事件体 `{groupId,inviterId,inviteeId}` 且**无 `bonusPoints`** / 无昵称头像）；重复填码 / 填自己 / 码不存在 / 老用户 / 活动未开始 / 已结束 / `enabled=0` **七条失败各断言固定业务码与中文文案**；失败不留行不发事件；大小写空格不敏感；参数校验 400；未登录 401 |
| `ReferralInviteeListIntegrationTest`（7） | `total`=人数、`created_at DESC`、昵称头像与 `groupedAt` 带出、**改名后名单跟着变（证明不是快照）**、空列表、分页、只看得到自己拉的人、页码越界被夹、未登录 401 |

- 测试设施落 `referral-app/src/test`（独立 project 挤不进 `bs-app`）：基类照 `PointsApiTestBase` 的形状
  （测试 Nacos 18848 写共享配置 + `System.setProperty` 注 Nacos 地址与 Dubbo 端口 + `@BeforeEach` TRUNCATE + 复位跨库活动配置）。
- **同步边的 seam = 测试源码里的 `@Primary StubUserQueryPort`**：拉新两条链路都依赖 `createdAt` / 昵称头像，
  真 Consumer 要单体作为 Provider 起着才通；桩让注册时间与展示信息可控。真跨进程调用归联调票（与票 05 的 Provider 同口径）。
- `scripts/test-env.sh up` 与 `reset-test-db` **实跑验证过**：三库建/重建、六个 `schema-<svc>.sql` 落位、
  `bs_lottery_test.lottery_activity` 种子就位；重建后再跑一次 23 用例仍全绿。
- **前端未动**：`/api/referral/**` 的页面调用归票 11；Vite 代理 `/api/referral → 8083` 由票 01 落好。

### code-review 采纳项

两轴并行，采纳 6 条：

1. **Spec 轴**：`INSERT IGNORE` 吞掉所有写错误 ⇒ 改成 `INSERT` + 只捕 `DuplicateKeyException`（见上「实现细节」）。
2. **Standards 轴**：`saveGrouped` 不「save」任何东西、实际是发 outbox 事件 ⇒ 改名 `publishGroupedEvent`。
3. **Standards 轴**：`saveGrouped(groupId, inviterId, inviteeId)` 三参又被打包回 `ReferralGroupedMessage`（Data Clumps）
   ⇒ 直接收 `ReferralGroupEntity`。
4. **Standards 轴**：票 06 把 `bs_lottery` 的建表塞进 `024_referral.sql`，破坏迁移号的服务归属与升序
   ⇒ 拆出 `023_lottery.sql`（见上「落地时定的三件事」第 1 条）。
5. **Spec 轴**：`docs/api/referral.md` 的失败表只写符号名、没写前端真正分支的 `4xxx` 数字 ⇒ 补数字列。
6. **Spec 轴**：越界 `page` 溢出报 999 ⇒ 夹上限（见上「实现细节」，附用例）。

未采纳（记录理由）：

- **没有迁移测试**：营销 schema 是「从无到有」建新库、与 `schema-*.sql` 内容一致，
  且 `MigrationTestBase` 在 `bs-app`（与营销 project 无 reactor）——**同票 05 的先例**，
  已在 `docs/db/README.md` 写明「等真要演进存量营销表时再补」。
- **outbox 三件套与积分服务逐字重复**：独立 project 之间无法共享实现，
  且 map 明写「outbox 三件套逐字照抄单体、只换包名」——这是既定口径不是疏漏。
- **`${table}` 表名拼接**（Primitive Obsession）：值取自应用配置、不含用户输入，且是 MyBatis 里唯一的表名拼接方式；
  已在 DAO 与 XML 两处写明「不是请求参数」。
- **活动闸门在查码之前**的次序：见上「实现细节」，spec §⑥ 支持这个次序。

### 已知波及 / 不在本票范围

- **票 07 必须往两个文件末尾追加、别重建**：`sql/schema-lottery.sql` 与 `sql/migrations/023_lottery.sql`
  （已是 `CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE`，重跑幂等）。票 07 的票面已加注记。
- **票 07 的 `lottery_draw_count_consume` 唯一键**仍待按票 05 的回填改（`(lot_id, reason_type, ref_id)`），与本票无关但别忘。
- **成团事件的真投递未验**：测试栈有 namesrv、没有 broker，发送必然 `No route info of this topic` 并被
  `OutboxMessageSender` 兜住 ⇒ 验收只看 **outbox 落行 + 载荷正确**。真投递 + 抽奖加次数 + 单体落通知归票 09。
- **Dubbo 真跨进程调用未验**：本票两条链路读的是测试桩；单体作为 Provider 的真调用归联调票（与票 05 同口径）。
- **`enabled=0` 与 `end_at` 之后共用一个 `ACTIVITY_ENDED`**：spec 原文即如此（「`enabled = 0` 同理」），
  不做区分，前端两种都出「活动已结束」。
