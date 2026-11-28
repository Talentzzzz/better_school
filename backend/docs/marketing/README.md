# 营销域文档总览

营销四域（points / draw / rebate / award）已随 monolith-restructure 票 05/06/07 **并入单体**
`backend/better-school/`（`com.betterschool` 平级领域包，marketing 命名空间退役）：
HTTP **8080**（单体唯一 app），Dubbo 50051，xxl 执行器 `better-school-executor`（9999），
库 **`better_school`**（测试 `better_school_test`；票 07 合库，`bs_marketing` 库名退役）。
（沿革：票 06 并入积分、票 07 并入抽奖、票 12 两段式库存（后续 award 重构收窄为单一奖品库存台账）、票 13 抽奖域整体落 `draw_*` 新模型；
**抽奖次数与抽奖同域**——`draw_count_*` 三张表与次数账务并入 draw，不再有独立的 drawcount 域
（`draw_count_config` 因此归 draw）。原 `config` 域只留行为返利并改名 `rebate`。
域下没有 `common` 段：跨域只读端口声明在消费方域。）

## 接口

| 域 | 文档 | 路径前缀 |
| --- | --- | --- |
| 积分（points） | [points.md](api/points.md) | `/api/points` |
| 抽奖（draw） | [lottery.md](api/lottery.md) | `/api/lottery` |
| 配置后台（两业务域各管一段：points / draw（次数）+ rebate） | [config.md](api/config.md) | `/api/marketing/config` |

- 免登录白名单只有两条（抽奖域）：`GET /api/lottery/activity`、`GET /api/lottery/prizes`；其余全部需登录
  （积分接口无例外）。抽奖域票 13 起**没有 ADMIN 接口**（旧「开桶」端点已删）；配置后台的写接口另有 ADMIN 校验。
- 抽奖域的写入口只有两条、口径互不交叉：`POST /api/lottery/draw` **只扣抽奖次数**；
  `POST /api/lottery/purchase/draw-count`（历史名 `/exchange/draw-count`）**用积分买次数**。
- 分页一律 `page`/`pageSize`，返回统一的 `Response{code,info,data}` 信封（各域共用一份 `types.common.ResponseCodeEnum`）。

## 库表

| 域 | 表集 | 表文档 |
| --- | --- | --- |
| 积分（points） | points_bucket / points_detail（桶模型）+ points_validity_config（积分有效期，单行） | [points.md](db/points.md) |
| 抽奖次数（归 draw 域） | draw_count_bucket / draw_count_detail（与积分同构）+ draw_count_config（次数有效期，单行）；抽奖只扣次数，积分换次数走「购买次数」 | [points.md](db/points.md) |
| 行为返利（rebate） | action_rebate_config（行为 × 返利类型 → 发多少分 / 多少次） | [points.md](db/points.md) |
| 抽奖决策侧 + 主链路 + 发货闭环（draw） | draw_* 6 张（票 11 表 + 责任链 + 预热；票 13 主链路与读侧；票 14 发货状态机） | [draw.md](db/draw.md) |
| 奖品库存（award） | 单一奖品库存台账 `award_inventory`(+detail) 2 张：1 奖品位 1 账户，运营直接 upsert 投放，抽奖预扣/确认唯一扣减（无配额池、无分配链路） | [award.md](db/award.md) |
| 跨域公共（MQ） | outbox_message（本地消息表，票 08 定型、票 14 起抽奖域写入） | 本文件下「事件」段 |

- DDL 来源：单体 `better-school/sql/schema.sql` **尾段**（全库唯一 DDL 事实源；营销并入 **20 张表**；
  营销独立 `marketing/sql/` 四件已随票 07 合库删除；hot-row-merge-l2w256 起为最终形态，
  uuid-biz-id 票 08 起存量库退役（删库重建），历史切换包 `sql/cutover/` 已删除；
  award 域重构删除 5 张池/分配表后，存量库同样删库重建）。
- 子域名与跨库纪律见根 `backend/docs/db/README.md`。

## 架构与规格

- [进程内合并（攒批）通用组件](architecture/merge.md)：`infrastructure/merge` 只做分片路由 / 攒批窗口 /
  单飞落库 + 五态回填，**落库函数由调用方传入**；接新用途三步、不改 `merge` 包。
- [超时中心接入规格](../architecture/timeout.md)：营销侧超时的**将来落法**（当前零接入方，本期不落表、不落代码）
  —— 库表 / 域段落位 / 路由 / 调度并行 / 与既有扫描 job（及已删除的每秒合并 job）的边界。

## 事件

**`outbox_message` 是全库唯一的本地消息表**（单体与营销域共写一份；形状取 marketing 超集，含
`delay_level` / `delay_ms`；`uk_msg_id` 幂等）。
口径（票 14 定型）：

- **写**：业务事务内 `INSERT`（`OutboxMessageStore.save`），提交后 `afterCommit` 异步发送一次；
  业务回滚则事件行随之消失——这是「不出现没发生的事的通知」的根。
- **重发**：`outboxScan`（`0/30 * * * * ?`）只捞 `state = 0` 且 `update_time` 超过
  `outbox.scan.timeout-seconds`（默认 60s，见 `OutboxMessageSender`）的行；`retry_count` 到 5 次置
  `state = 2`（死信，不再重发，靠 `idx_state` 捞出来人工处理）。
- **消费**：同一 topic 的多个订阅方**各用一组**（生产 / 积分 / 单体三方，组名见 `types.*.Constants`）。

