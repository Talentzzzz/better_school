# 10: 业务侧提交通道（三接入点写 outbox）

**What to build:** better-school 单体的三处支付超时改走超时中心注册。better_school 库建 `timeout_register_outbox`（DDL spec §7.6）；三个接入点在创建事务内写 outbox（spec §8 接入点表）：商品订单与跑腿**替代现 outbox 写入**，求购**补注册**（现状无注册、仅周期扫描兜底）；事务提交后 afterCommit 即时发送（挂钩必须挂在提交后，spec §7.1）+ 发送方 `@XxlJob` 兜底扫描 handler（30s 周期、5s 宽限期）+ 无上限重试、积压量告警（spec §7.1）。taskType 三常量 `PRODUCT_ORDER_PAY_TIMEOUT` / `WANT_TO_BUY_PAY_TIMEOUT` / `ERRAND_PAY_TIMEOUT` 落 bs-types 的 Constants（spec §7.4、§8）。降级口径：中心不可用照常下单、注册行积压 PENDING 持续重试、恢复自动追平（spec §7.5）。

**注意切换点**：本票替代现写入后，旧 RocketMQ 链路不再收到新消息（事实切换，符合 spec"一次性下线不留双跑"）；旧链路代码保留不动，移除归迁移 effort（spec §10）。

**Blocked by:** 07（工程骨架与任务 API）

**Status:** resolved

- [x] 三类下单各自事务内写 outbox：商品订单与跑腿不再写旧 outbox、求购补上注册；事务回滚则 outbox 行一并回滚（无幽灵注册）
- [x] 事务提交后即时发送：单元素列表调中心批量注册 API，成功置 SENT
- [x] 即时发送失败 → 30s 兜底扫描接管（5s 宽限期防双路径竞态），重试无上限、永不 DEAD
- [x] 中心停机时下单照常成功，outbox 积压 PENDING；中心恢复后自动追平注册（含 action_time 已过期的立即触发补关单）
- [x] outbox 积压量告警日志点就位
- [x] 全链路验收：三类下单 → outbox → 中心可见已注册任务，callback_group = bs 应用名

## Answer

2026-09-19 落地。**新增**：`bs-types` 的 `Constants` 三个 taskType；`sql/schema.sql` + `sql/migrations/028_timeout_register_outbox.sql`
（spec §7.6 DDL 逐字）+ `docs/db/outbox.md`；infrastructure 的 `dao/outbox/timeout/`（PO + DAO）、
`outbox/TimeoutRegisterOutboxStore`、`adapter/port/timeout/`（`ITimeoutTaskRegisterPort` +
`TimeoutTaskRegisterPort`，后者持 `@DubboReference(check=false, timeout=2000)`）、
`mq/TimeoutRegisterOutboxSender`（即时发送 + `@XxlJob("timeoutRegisterOutboxScan")` 扫描 + 取消 + 积压告警）；
mapper `TimeoutRegisterOutboxMapper.xml`；`xxl-job-tasks.sql` 加扫描任务行；单体 pom 引 `timeout-center-api`
（bs-infrastructure 另补 dubbo starter，形状照 marketing-infrastructure）。
**改**：三个接入点——`ProductOrderServiceImpl.createOrder` / `ErrandOrderServiceImpl.createOrder` 把
`sendPayTimeoutDelayMessage` 换成 `schedulePayTimeout`（旧方法从两个仓储接口与实现里删掉），
`WantToBuyServiceImpl.publishWantToBuy` 补事务包装 + `submitWantToBuyDraft` 补事务包装并登记（求购两条入口）；
`application.yml` 补 `timeout-center.register.*` 四项；`application-test.yml` 错开 executor appname；
`CommunityApiTestBase` 的 TRUNCATE 列表补表；docs（`db/README.md`、`db/trade.md`、`db/errand.md`、
`architecture/timeout-center.md` §8、`backend/CLAUDE.md` §4 例外一行）。

完成线：`mvn -o test` **347/347 绿**（新增 `TimeoutRegisterOutboxIntegrationTest` 5 用例 +
`TimeoutRegisterOutboxMigrationTest` 1 用例；前一次全量 346 已含前者首轮的 4 个，补「取消重试」用例后 +1）。

### 两处用户拍板的设计分叉（本票开场问的）

1. **求购注册点 = 直接发布 + 草稿提交两处**（不只 spec §8 字面的"创建事务内"）：求购进 `PENDING_PAYMENT`
   有两条入口，而旧扫描兜底认的 `created_at` 正是 `submitDraft` 会刷新的那一列——只注册直接发布会让草稿
   路径没有超时注册。
2. **同键二次登记 = 复位同一行**（`INSERT ... ON DUPLICATE KEY UPDATE`：status 回 PENDING、`action_time`/
   `created_at` 刷新、`retry_count` 清零），biz_id 保持纯 `wantId`；不走"biz_id 编码支付轮次"那条路
   （中心会按轮次留多条历史任务、回调要解复合 id）。

### code-review 揪出的真货：复位对中心侧不生效（已按用户拍板补取消）

Spec 轴发现：**「复活」只对终态任务成立**——旧任务还在 `WAITING/RETRYING` 时重新注册，中心按「同 group
幂等成功应答、**不改期**」处理（spec §3.4），新 `action_time` 被静默忽略。求购支付后撤回 → 草稿 → 重新提交
正好落在这个窗口里（都在原 15 分钟窗口内），旧任务到点会把刚重新提交的求购**提前关单**。
用户拍板补 spec §8 的求购取消点：支付事务提交后 `cancelPayTimeout` → `ITimeoutTaskRegister.cancel`
（best-effort 异步）。**商品/跑腿不调**（订单 id 每次新建，不存在同键复位；spec 给它们的取消点属纯优化）。

**取消要重试**（`cancel-attempts`/`cancel-retry-millis`，3 次 × 1s ≈ 注册单次调用超时 2s）：联调冒烟实测
抓到竞态——支付紧跟发布时注册**还在途**，中心没有这一行、撤销返回 false（日志「取消注册任务未生效」，
而库里那行随后被标 SENT）。补重试后同一冒烟脚本复跑：求购任务在中心置 **CANCELED**，其余两条 WAITING。

**已知残留（写进 architecture/timeout-center.md §8，不再在票 10 内解）**：注册在重试窗口之后才落地
（中心停机期间行停在 PENDING、30s 后扫描补投），或某次注册的响应晚于取消到达中心（取消后注册会被中心
按「复活」重开）——陈旧任务仍在，旧时刻会提前关单。无资金/数据损失，用户重新提交即可。彻底消除要么动
中心侧语义（同 group 非终态重注册改期 = spec §3.4 的变更），要么在业务侧做"注册后回查意图"的持久握手，
两者都超出本票。

### 其余 code-review 采纳项

- **Standards**：`ITimeoutTaskRegisterPort` 没有 domain 调用方（域层只写自己仓储的 outbox 行），
  接口留在 infrastructure 同包——已把这条例外写进 `backend/CLAUDE.md` §4，并在 §8 记明
  `@XxlJob` 留在 `infrastructure/mq/` 的原因（`bs-trigger` 不依赖 `bs-infrastructure`，handler 只能跟着
  发送器走，同 `OutboxMessageSender` 既有先例）。
- 删掉 `TimeoutRegisterOutboxStore` 里 `setRetryCount(0)`（mapper 里是字面量 `0`，该赋值从不被读）。
- 文档补 跑腿（`db/errand.md`）与求购（`db/trade.md`）的接入行。

**未采纳（判断项）**：`taskType` 保持 `Constants` 字符串常量而非枚举（spec §8 就是这么定的；回调侧按
taskType 分发的枚举化留给票 11 一起看）；三个仓储里 `save(常量, String.valueOf(id), ...)` 的一行重复
（每个仓储声明自己登记哪个 taskType 是特性，不为消去 `String.valueOf` 造枚举 + long 参数）；
测试夹具（adminSession/建商品/下单）仍留在本测试类内（同目录既有用例都是这个形状，只有基类共享）；
发送器留在 `infrastructure/mq/`（与两个既有 outbox 发送器同处，名字的"mq"是既有瑕疵）。

### 全链路实跑验收（本机，真实 Dubbo，非测试桩）

`docker exec … < sql/migrations/028…`（dev 库补表）+ 重新导入 `xxl-job-tasks.sql`（新任务行 id=14，启用）
→ `mvn clean package` → 起 8080 单体 + 8083 中心（中心 jar 是既有构建，本票没动中心）→
`.scratch/timeout-center/smoke-ticket10.py`（真实验证码登录 + 建商品/裁决/下单/建跑腿/建求购/求购支付）：

- `timeout_register_outbox`：三行 `SENT`、`callback_group=better-school`、`action_time = 下单时刻 + 15min`；
- `bs_timeout.timeout_task_{00,06,15}`：三条任务 `WAITING`、`callback_group=better-school`、`action_time` 与
  业务侧一致 —— **真实 Dubbo 注册跨进程通了**（这是集成测试的假端口盖不到的那一段）；
- 求购支付后：该任务置 `CANCELED`（真实 Dubbo 取消），商品/跑腿两条仍 `WAITING`；
- `delay_outbox_message` 两个支付超时 topic：**13:25 之后零新行**（事实切换成立；库里 26 条全是 8/30–9/12 的旧行）；
- xxl-job admin 的 `xxl_job_log`：`timeoutRegisterOutboxScan` 每 30s 真触发、返回 success
  （首两次 Address Router Fail 是执行器尚未注册，属启动竞态）。
- 中心侧扫描到点（13:44）把前一轮三条任务打成 `RETRYING` —— 预期：回调实现是票 11，现在没有 provider。

给后续票的两条环境提示：① **dev 库要手工跑 028**（`test-env.sh reset-test-db` 只重建测试库）；
② 冒烟脚本按"日志最新改动的那份"抓验证码（重复读同一条会拿到已消费的旧码）。

## Comments
