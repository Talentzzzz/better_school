# 营销服务文档总览

一个 app 两业务域（points / raffle）+ 两支撑域（rebate / inventory）
（票 06 并入积分、票 07 并入抽奖、票 12 两段式库存、票 13 抽奖域整体落 `draw_*` 新模型；
**抽奖次数与抽奖同域**——`draw_count_*` 三张表与次数账务并入 raffle，不再有独立的 drawcount 域
（`draw_count_config` 因此归 raffle）。原 `config` 域只留行为返利并改名 `rebate`。
域下没有 `common` 段：跨服务/跨域只读端口声明在消费方域）：
HTTP **8081**，Dubbo 50052，xxl 执行器 `marketing-executor`（9998），库 **`bs_marketing`**（测试 `bs_marketing_test`）。

## 接口

| 域 | 文档 | 路径前缀 |
| --- | --- | --- |
| 积分（points） | [points.md](api/points.md) | `/api/points` |
| 抽奖（raffle） | [lottery.md](api/lottery.md) | `/api/lottery` |
| 配置后台（两业务域各管一段：points / raffle（次数）+ rebate） | [config.md](api/config.md) | `/api/marketing/config` |

- 免登录白名单只有两条（抽奖域）：`GET /api/lottery/activity`、`GET /api/lottery/prizes`；其余全部需登录
  （积分接口无例外）。抽奖域票 13 起**没有 ADMIN 接口**（旧「开桶」端点已删）；配置后台的写接口另有 ADMIN 校验。
- 抽奖域的写入口只有两条、口径互不交叉：`POST /api/lottery/draw` **只扣抽奖次数**；
  `POST /api/lottery/purchase/draw-count`（历史名 `/exchange/draw-count`）**用积分买次数**。
- 分页一律 `page`/`pageSize`，返回统一的 `Response{code,info,data}` 信封（各域共用一份 `types.common.ResponseCode`）。

## 库表

| 域 | 表集 | 表文档 |
| --- | --- | --- |
| 积分（points） | points_bucket / points_detail（桶模型）+ points_validity_config（积分有效期，单行） | [points.md](db/points.md) |
| 抽奖次数（归 raffle 域） | draw_count_bucket / draw_count_detail（与积分同构）+ draw_count_config（次数有效期，单行）；抽奖只扣次数，积分换次数走「购买次数」 | [points.md](db/points.md) |
| 行为返利（rebate） | action_rebate_config（行为 × 返利类型 → 发多少分 / 多少次） | [points.md](db/points.md) |
| 抽奖决策侧 + 主链路 + 发货闭环（raffle） | draw_* 6 张（票 11 表 + 责任链 + 预热；票 13 主链路与读侧；票 14 发货状态机） | [draw.md](db/draw.md) |
| 库存（inventory） | award-inventory 两层模型：配额池 `virtual_inventory_quota_*` / `physical_marketing_quota_*` 各 2 张 + 分配流水 `inventory_allocation_flow` + 奖品库存统一台账 `draw_strategy_award_inventory`(+detail) 2 张（抽奖只扣奖品库存账户，池只被分配转移） | [inventory.md](db/inventory.md) |
| 跨域公共（MQ） | outbox_message（本地消息表，票 08 定型、票 14 起抽奖域写入） | 本文件下「事件」段 |

- DDL 来源：本工程 `sql/init-{dev,test}.sql`（只建库）+ `sql/schema.sql`（唯一 DDL 主体，21 张表；
  票 13 起 `schema-legacy.sql` 已删除，旧域九张表退出；hot-row-merge-l2w256 起为最终形态，
  uuid-biz-id 票 08 起存量库退役（删库重建），历史切换包 `sql/cutover/` 已删除）。
- 子域名与跨库纪律见根 `backend/docs/db/README.md`。

## 架构与规格

- [超时中心接入规格](architecture/timeout.md)：营销侧超时的**将来落法**（当前零接入方，本期不落表、不落代码）
  —— 库表 / 域段落位 / 路由 / 调度并行 / 与既有扫描 job（及已删除的每秒合并 job）的边界。

## 事件

**`outbox_message` 是营销服务自己的本地消息表**（一服务一份，与单体各写各的；`uk_msg_id` 幂等）。
口径（票 14 定型）：

- **写**：业务事务内 `INSERT`（`OutboxMessageStore.save`），提交后 `afterCommit` 异步发送一次；
  业务回滚则事件行随之消失——这是「不出现没发生的事的通知」的根。
- **重发**：`outboxScan`（`0/30 * * * * ?`）只捞 `state = 0` 且 `update_time` 超过
  `outbox.scan.timeout-seconds`（默认 60s，见 `OutboxMessageSender`）的行；`retry_count` 到 5 次置
  `state = 2`（死信，不再重发，靠 `idx_state` 捞出来人工处理）。
- **消费**：同一 topic 的多个订阅方**各用一组**（生产 / 积分 / 单体三方，组名见 `types.*.Constants`）。

