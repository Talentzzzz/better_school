# Map：超时中心（timeout-center）

_Label: `wayfinder:map`_

## Destination

一份 spec：`.scratch/timeout-center/spec.md`，定义**超时中心微服务**（独立 Spring Boot app）自身的完整设计 + **业务接入契约**（公共模块/SDK 的接口约定），现有三处支付超时（商品订单、求购、跑腿）作为首个接入方落进契约。**不含**业务迁移与旧链路下线步骤（另开 effort）。

## Notes

- **现状基线**（2026-09-18 调查）：三处 15 分钟支付超时（商品 `ProductOrderServiceImpl` / 求购 `WantToBuyServiceImpl` / 跑腿 `ErrandOrderServiceImpl`，均为硬编码常量）。现链路 = 下单事务写 `delay_outbox_message` 表 → xxl-job `delayOutboxScan` 每 30s 扫表发 RocketMQ → `ProductPayTimeoutConsumer` 关单 + 每分钟 `productTimeoutScan` 扫表兜底。关单动作：条件 UPDATE 取消 + 商品回在售 + 搜索同步消息；无退款/优惠券/积分。（⚠️ 2026-09-18 票 05 核实修正：写 outbox 的只有商品 `ProductOrderServiceImpl:55` 与跑腿 `ErrandOrderServiceImpl:63`；**求购无任何注册**，仅 `TradePublishCase:263` 周期扫描兜底）
- **图绘期已定决策**（2026-09-18 grilling 首轮）：
  - spec 覆盖 = 超时中心设计 + 接入契约，**不含迁移**（Q1→C）
  - 工程形态 = 独立 Spring Boot app（仓库新顶层工程），先用同 MySQL 容器内的**独立库**，不独立容器（Q2）
  - 回调协议 = **已拍板**（2026-09-18 票 02）：Dubbo 直调 + `timeout-center-api` 泛化接口 + group 路由，细节在该票 ## Answer 里
  - 将来迁移 = 一次性下线旧链路，不留双跑观察期（Q4；决策记录在案，动作本身出 scope）
  - 通用中心：数据模型与回调协议按任意业务超时抽象（task_type + payload + 回调），spec 只落三处支付超时接入；跑腿执行时限等不接（Q5）
- **工作方式**：grilling 票每轮问 frontier、每个问题给推荐答案；调研先行，拍板票都被调研票阻塞。
- 求职作品，真实产品标准；架构取向偏"更贵更能展示"，但代价要先讲清。

## Decisions so far

- [调研：阿里超时中心与业界延时任务方案](issues/01-research-alibaba-timeout-center.md)：业界"超时中心"= 超时任务库 + 分片扫表 + at-least-once 回调（幂等在被回调方，重试退避 + 死信兜底）；报告收有阿里两篇一手文章、xxl-job 分片广播源码级细节、四方案对比与资料缺口清单
- [grilling：回调协议拍板](issues/02-grill-callback-protocol.md)：Dubbo 直调（tri + Nacos）；`timeout-center-api` 泛化接口，业务引 jar 实现暴露、中心按 `callbackGroup` 动态 ReferenceConfig 路由（新业务接入零代码变动）；约束：接入方与中心共用同一 Nacos 集群。引用 id payload、批量回调 + 成功/失败双清单、幂等分工（业务方条件更新）、16 次指数封顶重试 → FAILED 死信 + 管理接口、成功置终态不删——细节在该票 ## Answer（含转票 03/04/05 锚点；重试列名已被票 03 回填为 `next_fire_time`。⚠️ 票 04 反转连带：回调 DTO 定稿三字段、双清单项定稿 `(taskType, bizId)` 对）
- [grilling：任务数据模型与生命周期拍板](issues/03-grill-task-model-lifecycle.md)：独立库 `timeout_task` 单表——状态机 5 态（WAITING/RETRYING → 终态 SUCCESS/FAILED/CANCELED，无 PROCESSING，重复触发靠业务幂等吸收）；单列 `next_fire_time` 统一扫描时刻（复合索引 `(status, next_fire_time)`，已回填票 02 列名）；独立 `VARCHAR` task_id（用户拍板：自增 id 零业务含义）；`uk(task_type, biz_id)` 幂等 + 终态复活语义；业务 API 仅注册/取消（取消可选，不取消也正确）；首版不归档（量级论证入 spec）；payload/attribute 列与分片键列均不建（分片方式留票 04）——细节在该票 ## Answer（含定稿 DDL 与转票 04/05/06 锚点）。⚠️ 2026-09-18 票 04 反转：task_id 删除（标识 = 自然键 task_type+biz_id）、单表 → 同库 16 分表（DDL 定稿 v2 见票 04 Answer），其余结论不变
- [grilling：分片扫描设计拍板](issues/04-grill-shard-scan-design.md)：同库 16 分表 `timeout_task_00..15`（库×表两维路由，DB_COUNT=1 起步）+ **ShardingSphere-JDBC 5.x** 承载路由（写入 complex 自定义算法 `hash(taskType+bizId)%16`、扫描 HintManager 强制路由，两算法同一编号空间）；xxl-job 分片广播 `槽位 g % shardTotal` 动态平分 + misfire FIRE_ONCE_NOW；单 job（扫描=重试=死信重发统一入口）10s 周期、无时间窗循环拉批 ≤200；**虚拟线程**并行扫描 fan-out + 回调独立 executor；防漏靠下轮重扫、防重四层（表级归属/DISCARD_LATER/job 重试 0/在途重发业务幂等）；删 task_id 反转票 03 标识——细节在该票 ## Answer（含定稿 DDL v2 与转票 05/06 锚点）
- [grilling：接入契约拍板](issues/05-grill-integration-contract.md)：提交通道 = **业务侧 outbox**（`timeout_register_outbox` 业务库新表，事务内 INSERT 原子注册；⚠️ 反转助手原推荐的 afterCommit 直调 + 内存重试队列）+ afterCommit 即时发送 + 扫描兜底（30s、5s 宽限期）+ 无上限重试积压告警；SDK 两模块（`timeout-center-api` 纯接口 + `timeout-center-client` 行为库）；注册 API **批量信封 + 双清单**，同键不同 group = 冲突失败 + 告警守卫；降级 = 照常下单不拒绝，中心恢复自愈；三接入方接入点表落契约——细节在该票 ## Answer（含 outbox 定稿 DDL 与转票 06 锚点）
- [task：spec 产出（effort 收口）](issues/06-task-write-spec.md)：**spec 已定稿 → [spec.md](spec.md)**（2026-09-18）。十章：范围 / 工程形态 / 数据模型与状态机（DDL v2）/ 任务 API / 扫描与调度 / 回调协议 / 接入契约 / 三接入方接入点 / 可观测性（开放项：堆积监控、告警平台、部署落位）/ Out of scope。汇总以编号更大的票为准（taskId 删除、批量信封、group 守卫等反转已按定稿落稿）。票 01–06 全 resolved，无剩余 frontier
- [落地：工程骨架与任务 API](issues/07-scaffold-and-task-api.md)：**已落地（2026-09-19，commit fd61f33）**。工程 `backend/timeout-center/` 七模块（照 backend/CLAUDE.md §1，非票面写的「两模块」——票面措辞未修订）+ 独立库 `bs_timeout`/`bs_timeout_test` 各 16 分表 + Dubbo `ITimeoutTaskRegister`（批量信封双清单 + 幂等四分支 + 取消软置 CANCELED）+ SS complex 分片路由。**踩坑与结论**：ShardingSphere 5.5.x 的 `shardingsphere-jdbc` 只是驱动壳，特性 artifact 要按它自己 pom 的 **test scope** 那批补（sharding-core / hikari 池元数据 / standalone 模式 / authority-simple / 方言解析器），另需覆盖 commons-lang3 ≥3.18.0；`tableNameOf` 少了 `_` 会让算法返回空集→退化成 16 表广播。集成测试 14/14 绿，环境供给已由 `test-env.sh` 的 `apply_service_init` 泛化承接
- [落地：重试退避与死信管理](issues/09-retry-and-dead-letter.md)：**已落地（2026-09-19）**。16 次封顶（可配）进 `FAILED` 死信 + 管理接口（HTTP 8083 `/api/admin/timeout-tasks`：死信列表 / 单条与批量重发 / 手动取消）+ 三条告警日志点。**两处用户拍板**：管理面的接口与 DTO/Response 落在 **trigger**（业务契约 jar `timeout-center-api` 保持「纯接口 + DTO」不被污染）、本版管理接口**无鉴权**（记入 spec §9 开放项）。重发 = `FAILED → RETRYING`、`retry_count` 清零、`next_fire_time = now`、**不动 `action_time`**，走同一个扫描 job 入口；死信列表是**唯一不塞 hint 的查询**（横跨 16 分表广播归并，spec §5.2）。测试 31/31 绿 + 真机 `java -jar` 冒烟（含「重发 → 真实扫描 job 10s 后重投」）。code-review 两轴各出真货（重试告警改按批汇总、页码封顶、删重复 DTO、响应体码值口径纠错），细节在该票 ## Answer
- [落地：扫描 job 与批量回调闭环](issues/08-scan-and-callback.md)：**已落地（2026-09-19）**。回调契约（`ITimeoutTaskCallback` + 三字段 `TimeoutTaskDTO` + 双清单）、动态 `ReferenceConfig` 按 group 路由（新业务零代码变动，实测通 tri + Nacos）、扫描 job（10s 分片广播 / 循环拉批到空 / 组间并行回调 / 就地对账）、退避重试（30s 起翻倍、封顶 1h）。**⚠️ 一处 spec 级偏离（用户拍板）**：§5.2 的「写入 complex + 扫描 hint」在 SS 5.5.3 做不到（一张表一个 tableStrategy；complex 表上 HintManager 实测不生效），改成**全量 hint 强制路由**（写也塞槽位），complex 算法删除——细节与证据在票面 ## Answer。测试 23/23 绿 ×4 轮 + 真实 xxl-job 跑通
- [落地：业务侧提交通道（三接入点写 outbox）](issues/10-business-outbox-submission.md)：**已落地（2026-09-19）**。单体建 `timeout_register_outbox`（迁移 028）+ `TimeoutRegisterOutboxStore`（事务内 upsert 复位 + afterCommit 即时发送）+ `TimeoutRegisterOutboxSender`（虚拟线程同步 Dubbo + 就地批量标记 + `@XxlJob` 30s 扫描 + 积压告警）+ `adapter/port/timeout` 的注册/取消边；三接入点接线（商品/跑腿替代旧 outbox 写入、求购两条入口补注册）。**两处用户拍板**：求购注册点=直接发布+草稿提交；同键二次登记复位同一行（biz_id 保持纯 wantId）。**⚠️ 一处 spec 级加法（code-review 揪出+用户拍板）**：求购支付后补发 `cancel`（spec §8 取消点，3×1s 重试）——否则同键复位对中心侧在途任务不生效（§3.4「不改期」会让旧任务提前关单）；存量残留窗口写进 architecture §8。测试 347/347 绿 + 真机三类下单→中心可见三任务（真 Dubbo）+ 求购支付后任务 CANCELED
- [落地：业务侧回调消费方（三路关单）](issues/11-business-callback-consumer.md)：**已落地（2026-09-19）**。`trigger/rpc/TimeoutTaskCallbackDubboService`（`@DubboService(group = "${spring.application.name}")`，按 taskType 分发三路关单 + 逐项隔离 + 双清单全量回报）+ `TradePublishCase.handleWantPayTimeout`（求购关单的 case 层入口，扫描与回调共用）+ bs-trigger 引 `timeout-center-api`；arch §9。**幂等零设施**（关单动作本身是条件 UPDATE，未命中=幂等成功，不建去重表）。**一处主动加法（spec 未规定）**：未注册 taskType / 坏 bizId 按失败回报 → 重投进死信暴露，不静默吞成成功。**逐项而非 §6.3 的批量 UPDATE**，换票面要求的「与现关单动作等价」（满批可能超中心 2s 超时 → 整组重投，幂等无副作用）。测试 352/352 绿 + 真机三路下单→到期→跨 Dubbo 回调→三条 SUCCESS、商品/跑腿/求购各关单动作生效（2.4s）
- [落地：扫描结算批量化（票 12）](issues/12-batch-settle.md)：**已落地（2026-10-03）**。结算从「逐行一条条件 UPDATE」改成**每批按结果分桶、每桶一条批量条件 UPDATE**（满批 200 条最多 600 条 SQL → ≤ 3 条）；三条 SQL 保留 `status IN ('WAITING','RETRYING')` 守卫，`TimeoutTaskEntity` 加不落库的 `shardSlot` 供批量语句塞 hint，重试桶 `next_fire_time` 走 `CASE`（各行退避不同）。**契约与并行模型零改动**（回报仍是回调 RPC 的返回值，`timeout-center-api` 一行未改）。**两个测试先抓出来的坑**：MyBatis 的 `#{list[i]}` 不取下标（要 `${i}`）、空桶会发 `IN ()`。测试：domain 离线单测 5（新增模块 test 依赖）+ app 集成 37，全绿

## 落地票（spec 定稿后另开，非图绘期票）

票 07–11 与 01–06 同目录（本 effort 没有单独的 `-impl/` 目录）：图绘票靠 `Type:` + `## Question/Answer`，
落地票靠 `Status: ready-for-agent`（今已全为 `resolved`）+「What to build」正文。**目录名判不了类型，看票面**。

- 07 工程骨架与任务 API（注册/取消）——**resolved 2026-09-19**
- 08 扫描 job 与批量回调闭环——**resolved 2026-09-19**（⚠️ 分片路由由 complex 改 hint，见票面 ## Answer）
- 09 重试与死信管理——**resolved 2026-09-19**（⚠️ 管理面落 HTTP、DTO/Response 落 trigger **不进 `timeout-center-api`**、本版无鉴权；见票面 ## Answer）
- 10 业务侧 outbox 提交通道——**resolved 2026-09-19**（⚠️ 求购支付后补发取消，spec §8 取消点；见票面 ## Answer）
- 11 业务侧回调消费方——**resolved 2026-09-19**（⚠️ 求购关单入口落在 `TradePublishCase`、未注册 taskType/坏 bizId 按失败进死信；见票面 ## Answer）
- 12 扫描结算批量化——**resolved 2026-10-03**（⚠️ 契约零改动、回报仍走 RPC 返回值；行级守卫保留、`shardSlot` 只用于塞 hint；见票面 ## Answer）
- **落地票 07–12 全 resolved，spec 定稿的整条链路已通（注册 → 扫描 → 批量回调 → 结算 → 三路关单），本 effort 无剩余 frontier**；
  仍在 scope 外的只有「旧链路下线」（迁移 effort，spec §10）与 §9 的开放项（堆积监控、告警平台、管理接口鉴权、部署落位）

## Not yet specified

- ~~超时中心自身的可观测性与运维面：任务堆积监控、告警接入监控平台是否进 spec（死信管理接口已随票 02 拍进 spec，余项仍开放）~~（✅ 2026-09-18 票 06 已处置：已拍项落 spec §9（死信管理接口 + 告警日志点 + outbox 积压告警），余项（堆积监控、告警平台接入）显式标注开放项"另议，不阻塞落地"）
- ~~部署细节：启动脚本 / 端口 / 建表脚本（独立库 DDL）的归属与落位~~（✅ 2026-09-18 票 06 已处置：spec §2 标注归落地票，spec 不锁）
- ~~接入 SDK 载体已随票 02 定为 `timeout-center-api` jar（业务引 jar 实现回调接口 + 注册带 `callbackGroup`）；仍待 05 拍：提交通道、注册流程细节、**超时中心挂掉时业务方的降级行为**~~（✅ 2026-09-18 票 05 已拍定：载体两模块 api + client；提交通道 = 业务侧 outbox；降级 = 照常下单不拒绝——细节在该票 ## Answer）
- ~~是否需要 prototype 票（扫表行为 / 重试语义的低成本演练）——视调研结果与拍板走向定~~（✅ 2026-09-18 effort 收口未开 prototype 票，未成为阻塞项）

## Out of scope

- **业务接入迁移与旧链路下线**（`delay_outbox` 表、`ProductPayTimeoutConsumer`、`productTimeoutScan` 兜底 job 的移除与切换步骤）：Q1 选 C，迁移另开 effort；已定将来一次性下线、不留双跑
- **跑腿执行时限、组队截止等非支付超时类型的实际接入**：通用性只在数据模型与协议层面，不落具体接入设计
