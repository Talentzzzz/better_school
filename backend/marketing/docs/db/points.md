# 积分 / 抽奖次数 schema（bs_marketing 的桶模型表集）

> 业务：积分与抽奖次数（桶模型，营销域，服务 `backend/marketing/`）；归属服务：marketing。
> 独立 schema（与单体同 MySQL 实例、不同库）。**跨 schema 硬约定：只存 id、不 join 单体库、不存昵称头像快照。**
> 票 10 起积分域三张旧表已删除（lot/明细/单行配置），积分域全面切 sql.md §2 的桶模型。

表结构 DDL：`sql/schema.sql`（**唯一 DDL 来源**；单行配置的种子也在该文件尾部）；建库文件
`sql/init-dev.sql` / `init-test.sql`。票 13 起 `sql/schema-legacy.sql` 已删除（旧域表整体退出）。

积分与抽奖次数是**同构的两套桶**：入账流水即事实源（桶表），消费按 `expire_at` FIFO
递减各桶 `remain` 并落明细（detail 表），没有余额列——余额 = 未过期桶的 `SUM(remain)`。

| 表名 | 说明 | 归属域 |
| ---- | ---- | ---- |
| points_bucket | 积分桶：每笔入账一行，**同时是发放流水与事实源** | points |
| points_detail | 积分消费明细：一次消费跨几个桶就有几行 | points |
| draw_count_bucket | 抽奖次数桶（与积分桶同构） | drawcount |
| draw_count_detail | 抽奖次数消费明细（一行 = 1 次，无 amount 列） | drawcount |
| points_validity_config | 积分有效期配置（单行 id=1，种子 365 天） | config |
| draw_count_config | 抽奖次数有效期配置（单行 id=1，种子 30 天） | config |
| action_rebate_config | 行为返利规则（签到/支付 → 发积分或次数） | config |
| outbox_message | 本服务自己的本地消息表（票 08 新形状） | common |

## points_bucket

| 字段 | 类型 | 约束 | 说明 |
| ---- | ---- | ---- | ---- |
| id | bigint | PK | 物理主键（纯内部键，永不对外） |
| bucket_id | varchar(36) | UNIQUE, NOT NULL | 桶业务 ID（UUID 字符串，应用层 `IIdGenerator` insert 前发号一次写入，uuid-biz-id 票 07 起——原雪花 Long 退役；消费明细与对外引用挂它） |
| user_id | varchar(36) | NOT NULL | 用户 ID |
| amount | int | NOT NULL | 入账积分数（恒正） |
| remain | int | NOT NULL | 该桶剩余可用（消费按 FIFO 递减，过期清理置 0） |
| expire_at | datetime | NOT NULL | 过期时间 = 入账时点 + `points_validity_config.points_valid_days` |
| reason_type | varchar(30) | NOT NULL | 入账来源：`LOTTERY_WIN` / `REBATE` |
| ref_id | varchar(36) | NOT NULL | LOTTERY_WIN = 抽奖记录 id（UUID 字符串，MQ 幂等键）；REBATE = 发放幂等键 |
| create_time / update_time | datetime | NOT NULL | 由应用写入 |

### 索引
- `uk_user_reason_ref (user_id, reason_type, ref_id)`：**入账幂等键**，MQ 重投/发放重试不重复入账
- `idx_user_expire (user_id, expire_at)`：余额求和与 FIFO 取桶
  （`WHERE user_id=? AND remain>0 AND expire_at>NOW() ORDER BY expire_at, id`）

### 注意
- **没有余额列**：余额 = `SUM(remain) WHERE remain > 0 AND expire_at > NOW()`。
  因此积分不可能为负，也不存在「余额列与流水对不上」的窗口
- **过期一律懒判**：所有读路径带 `expire_at > NOW()`；`pointsExpireScan` 任务只把过期桶 `remain` 置 0
  （**不删行、留审计、不发事件**）
- 消费顺序固定 `ORDER BY expire_at ASC, id ASC FOR UPDATE`：写路径必须先加行锁再改 `remain`；
  同一笔消费**明细先行**（先落齐 `points_detail`，再改各桶 `remain`），撞 `uk_bucket_reason_ref`
  时余量一行未动、整笔回滚
- 一笔消费的写路径**至多三条语句**（跨多个桶三条、跨 1 个桶两条），与跨几个桶无关：① 一条多值
  `INSERT IGNORE` 写全部明细（影响行数必须等于明细条数）② 一条 `UPDATE ... SET remain = 0
  WHERE bucket_id IN (...)` 清零扣光的桶（影响行数必须等于扣光集合大小）③ 一条
  `UPDATE ... SET remain = remain - ?` 扣那张部分扣的桶（恰好整笔扣完时这条跳过）。
  任一影响行数对不上即整笔回滚，不静默继续
- 与旧模型的差异：新增 `bucket_id` 业务 ID 列 + `update_time`；`uk_user_reason_ref` 语义不变

## points_detail

| 字段 | 类型 | 约束 | 说明 |
| ---- | ---- | ---- | ---- |
| id | bigint | PK | 物理主键 |
| user_id | varchar(36) | NOT NULL | 用户 ID |
| bucket_id | varchar(36) | NOT NULL | 被扣的**桶业务 ID**（FIFO 顺序；不再引用物理自增 id） |
| amount | int | NOT NULL | 本次从该桶扣掉的量（恒正） |
| reason_type | varchar(30) | NOT NULL | `REDEEM_DRAW_COUNT` / `REDEEM_PRIZE` |
| ref_id | varchar(36) | NOT NULL | 兑换单 id / 抽奖记录 id（UUID 字符串，兑换幂等键） |
| create_time / update_time | datetime | NOT NULL | 由应用写入 |

### 索引
- `uk_bucket_reason_ref (bucket_id, reason_type, ref_id)`：同一桶同一笔业务不重复扣。
  **不是 `(user_id, reason_type, ref_id)`**——一笔兑换跨桶会产生多行，按用户维度的 uk 会把第 2 行顶掉
- `idx_user_ref (user_id, reason_type, ref_id)`：兑换边幂等预检（`LIMIT 1`，普通快照读、**不加锁**）；
  它只是快路径，并发重复的互斥点是上面那条 `uk_bucket_reason_ref`——间隙锁彼此兼容、拦不住并发
- `idx_bucket (bucket_id)`

## draw_count_bucket / draw_count_detail

与积分侧**逐列同构**（票 10 只建能力，抽奖业务接入归票 13；uuid-biz-id 票 07 起 bucket_id / user_id / ref_id 同为 `varchar(36)` UUID 字符串）：

- `draw_count_bucket.reason_type`：`REDEEM`（积分兑换，ref_id = 兑换单 id，与 `points_detail`
  的 REDEEM_DRAW_COUNT **同值同事务写入**）/ `REBATE`（ref_id = 发放幂等键）
- `draw_count_detail` **没有 amount / reason_type 列**：每次抽奖恒扣 1 次，一行即 1 次；
  `uk_bucket_ref (bucket_id, ref_id)`，ref_id = 抽奖记录 id（draw_record.record_id）
- 有效期 = 入账时点 + `draw_count_config.draw_count_valid_days`；过期扫描 handler 为
  `drawCountBucketExpireScan`（与旧表的 `lotteryDrawCountExpireScan` 并存到票 13）

## points_validity_config / draw_count_config

| 字段 | 类型 | 约束 | 说明 |
| ---- | ---- | ---- | ---- |
| id | bigint | PK | 单行配置（种子恒为 1） |
| points_valid_days / draw_count_valid_days | int | NOT NULL | 有效期天数，种子 **365** / **30** |
| create_time / update_time | datetime | NOT NULL | 由应用写入 |

缺行视为系统错误（种子或迁移没跑），仓储抛异常而不是静默取默认值。

## action_rebate_config

| 字段 | 类型 | 约束 | 说明 |
| ---- | ---- | ---- | ---- |
| id | bigint | PK | 主键 |
| action_type | varchar(16) | NOT NULL | 行为类型 `sign`(签到) / `pay`(支付) |
| rebate_type | varchar(16) | NOT NULL | 返利类型 `DRAW_COUNT` / `POINTS` |
| rebate_name | varchar(64) | NOT NULL | 返利名称 |
| rebate_desc | varchar(128) | NULL | 返利描述 |
| rebate_value | int | NOT NULL, DEFAULT 0 | 返利值（抽奖次数、积分值） |
| config_id | bigint | NOT NULL | 有效期配置 id（POINTS → points_validity_config.id；DRAW_COUNT → draw_count_config.id） |
| state | varchar(12) | NOT NULL | `open`(开启) / `close`(关闭) |
| create_time / update_time | datetime | NOT NULL | 由应用写入 |

- `uk_action_rebate (action_type, rebate_type)`：同一行为同一返利类型一条规则（upsert 以它为键）
- 谁在写：仅后台管理面（`POST /api/marketing/config/rebates`，ADMIN）；桶域入账只读
  （按 `(action_type, rebate_type)` 查开启规则决定发多少、发不发）

## outbox_message

形状见 `schema.sql`（票 08 新形状：`destination` / `state` 0/1/2 / `retry_count`，`msg_id` 唯一键）。
积分域的 topic：`bs-points-changed`（变动事件，单体消费后落通知）；
`reasonType` 取值见 `PointsReasonType`：入账 `LOTTERY_WIN` / `REBATE`，出账 `REDEEM_DRAW_COUNT` / `REDEEM_PRIZE`。
