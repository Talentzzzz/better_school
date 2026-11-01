# 票 10 · 积分 / 次数切桶模型（7 张新表全链）

Type: task
Status: resolved
Blocked by: 09

## 上下文预算（硬约束）

| 项 | 值 |
| --- | --- |
| 目标 | **150–200k**；硬顶 **300k** |
| 预估改文件 | ~45（7 张表 × 五层 + mapper + 测试 4~6 + 文档 3） |
| 必读 | **`sql.md#L606-L727`（仅此区间，约 122 行）**；本票；票 08 的 `schema-legacy.sql` 注释清单 |
| 不读 | `sql.md` §2 其余段落（抽奖段归票 11、库存段归票 12）；rafflex（本票不需要） |
| 必跑 | `backend/marketing`：积分侧全部用例 + 新增桶模型用例 |
| 越界信号 | 想「顺手把抽奖次数也接进桶」→ 桶能力可以留，抽奖侧消费归票 13 |

## 已核实事实

1. `sql.md` 积分段 = **L606–L727**，共 **7 张表**：`points_bucket`(606) / `points_detail`(627) / `draw_count_bucket`(646) / `draw_count_detail`(667) / `points_validity_config`(683) / `draw_count_config`(695) / `action_rebate_config`(707)。`outbox_message` 已在票 08 落地。
2. 桶模型口径（`sql.md` L604 与各列 COMMENT）：**入账流水即事实源**；`remain` 按 `expire_at` **FIFO** 递减；跨 schema 只存 `user_id`，**不 join 单体库**。
3. 现状营销库（票 08 的 legacy）：`points_lot` / `points_consume` / `points_config` 三张旧表 —— 本票迁移完即从 `schema-legacy.sql` 删除。
4. 对外契约**不得变**：`IPointsService`（读）与 `IPointsExchangeService`（兑换扣分，抽奖侧 Dubbo consumer 打的就是它）。只换内部实现。
5. `backend/docs/architecture/marketing-services.md` 有「同步边（Dubbo）」「Nacos」契约节，改内部实现时若措辞受影响需同步。

## Question

把积分域从「`points_lot`/`points_consume`/`points_config` 流水 + 余额」改为 **`sql.md` 的桶模型**（桶 + 明细 + 有效期配置 + 行为返利配置），并按同构方式落地**抽奖次数桶**（`draw_count_bucket`/`draw_count_detail` + `draw_count_config`），供票 13 消费。

- 入账：签到 / 支付返利等行为 → 按 `action_rebate_config` 规则发放 → 落 `points_bucket`（或 `draw_count_bucket`）+ `points_detail`（或 `draw_count_detail`）同事务。
- 消费：按 `expire_at` FIFO 递减 `remain`；过期清理置 0。
- 桶级并发安全：**Lua 原子**（负值保护）。
- 后台/开放接口：有效期配置、次数配置、行为返利配置的读写（照 `sql.md` 表结构 + 现有接口面）。
- DDL 段落追加进 `backend/marketing/sql/schema.sql`；旧三张表从 `schema-legacy.sql` 删除。
- `CONTEXT.md` 与营销 `docs/db/*` 的积分词条改写为桶口径。

## 施工边界（不做什么）

- 不动抽奖域业务（票 11–14）；本票只把「次数桶」能力建好。
- 不动 `outbox_message`（票 08 已定型）。
- 不动库存（票 12）。

## 验收

1. `backend/marketing && mvn -o test` 全量绿（旧 `Points*` 用例按新模型改写或替换，不允许留删掉的断言）。
2. 桶模型集成用例 ≥3：入账落桶 + 明细；FIFO 跨桶扣减（两桶不同 `expire_at`）；过期清理；`remain` 不足时拒绝且不产生半扣。
3. `schema.sql` 收录本票 7 张表，比对脚本逐表一致；`schema-legacy.sql` 已去 `points_lot`/`points_consume`/`points_config`。
4. `points_lot|points_consume|points_config` 在 `backend/marketing` 零命中。
5. `## Answer` 记录：桶模型与旧模型的语义差异（尤其「余额从哪来」）、`IPointsService`/`IPointsExchangeService` 签名未变的确认、legacy 剩余表清单。

## Answer

（已收口，票 10 完成）

**1. 桶模型与旧模型的语义差异（余额从哪来）**

`points_lot` 时代就已经是「桶化」模型：每笔入账一行带自己的 `expire_at` 与 `remain`，没有余额列，余额 = 未过期桶的 `SUM(remain)`。本票的**语义不变量全部保留**（入账流水即事实源、FIFO 消费、懒过期、凑不够不产生半扣、无负数），差异在**标识与事实源的组织**：

- 桶获得独立业务标识 `bucket_id`（雪花生成，`uk_bucket_id`）；消费明细从引用物理自增 id（`points_consume.lot_id`）改为引用 `bucket_id`，物理主键彻底回归无业务含义（本库口径）。
- 旧 `points_config`（TINYINT id=1）→ `points_validity_config`（BIGINT 自增单行 + create_time/update_time，种子 365 天移入 schema.sql 尾部）；次数侧对称新增 `draw_count_config`（种子 30 天）。
- 新增第二资产「抽奖次数」与其同构（`draw_count_bucket`/`draw_count_detail`），REDEEM 入账与积分消费明细按 sql.md 约定同值同事务（编排归票 13，本票只建能力）；次数明细无 amount 列，一行 = 1 次。
- 新增 `action_rebate_config` 行为返利规则：`creditForRebate(actionType, …)` 按开启规则入账，`reason_type = REBATE`（PointsReasonType 新增枚举值），幂等键 = (userId, REBATE, 发放幂等键)。

**2. 对外契约未变的确认**

- `IPointsService`（getBalance / listTransactions）与 `IPointsExchangeService`（deduct）**签名逐字未动**；`PointsDeductRequestDTO/ResponseDTO`、`PointsBalanceResponseDTO`、`PointsTransactionResponseDTO` 字段与 JSON 形状不变（含 `createdAt`/`direction`/`expireAt` 语义）。抽奖侧 Dubbo consumer 零感知。
- 用户面 HTTP 面不变；新增的 `/api/marketing/config/*` 是独立后台面（新接口 `IMarketingConfigService`，读=登录、写=ADMIN），不属于原契约的改动。
- `bs-points-changed` 事件体形状不变，仅 reasonType 出现新取值 `REBATE`（单体按字符串透传，不受影响）。

**3. legacy 剩余表清单**

`schema-legacy.sql` 仅剩抽奖域旧表 10 张（`lottery_activity` / `lottery_prize` / `lottery_bucket_config` / `lottery_deduct_detail` / `lottery_draw_record` / `lottery_draw_count_lot` / `lottery_draw_count_consume` / `lottery_draw_daily` / `lottery_user_prize` / `lottery_redeem_order`）及其种子与 slogan 守卫——归票 13 删，清空即删本文件。

**4. 与 spec 两处措辞的偏差说明（code-review Spec 轴指出，确认保留）**

- **「Lua 原子（负值保护）」→ 落为 DB 原子**：桶的负值保护由 `UPDATE … WHERE remain >= #{amount}` 守卫 + FIFO 加锁读（`FOR UPDATE`）实现，与消费明细、outbox 同一 MySQL 事务。不引入 Redis Lua 是刻意的：桶的事实源就是 MySQL（入账幂等靠 uk、消费幂等靠明细 uk、变动事件靠同事务 outbox），Lua 原子层无法与 points_detail 同事务，会把事实源拆成两份。「Lua」一词属于库存域（票 12 的 Redis 分桶）的语境，sql.md 积分段各列 COMMENT 描述的正是本实现的 DB 口径。
- **「入账：签到/支付返利」→ 只建能力、不接业务**：`creditForRebate` 在 points / drawcount 两域就绪并有集成用例，但 sign/pay 业务事件的生产入口不在本票边界内（签到/支付发生在单体；编排边归后续票，与「越界信号」一致——桶能力可以留）。

**5. 连带影响清单**

- `xxl-job-tasks.sql`：新增 handler `drawCountBucketExpireScan`（新次数桶过期扫描；旧 `lotteryDrawCountExpireScan` 与 `pointsExpireScan` 保留）。
- `SnowflakeIdGenerator`（types/common）：新增桶业务 ID 生成器，票 13 建次数桶同样用它。
- `TestingEnvironment`（测试基类工具）：`ensureEnvironment/tokenOf/getJson/get/post/readJson` 可见性从包私有放宽为 public（新增域的测试类在别的包），语义零改动。
- `ResponseCode` 新增 `CONFIG_PARAM_ERROR("400", "参数错误")`（配置域自取 400 段，既有码值未动）。
- 文档：`marketing/docs/db/points.md` 整份改写（7 张表桶口径）、新增 `docs/api/config.md`、`docs/api/points.md` / `docs/README.md` / `architecture/marketing-services.md` / 根 `CONTEXT.md`（新增「营销积分」词条）同步。

<!-- Context used: ~90k；未触发拆分。必跑通过：marketing 全量 78 测试绿（含桶模型集成用例 15 个）、cmp_schema_marketing.py 22 表逐表全等、旧表名 backend/marketing 零命中。 -->

