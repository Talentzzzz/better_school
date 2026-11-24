# Spec：合并引擎 per-invId leader 模型改造（对齐数据库内核热点组提交）

> 状态：ready-for-agent（待确认测试 seam 后开工）
> 决策来源：2026-11-23 引擎评审对话——与 PolarDB-X《如何支持淘宝库存热点更新》、淘宝数据库内核月报《RDS 三节点企业版热点组提交》逐层对齐后的拍板。

## Problem Statement

现有热点行攒批引擎（固定 8 分片哈希桶 + 常驻后台线程 + 合并窗口）在单热点场景机制正确，但有两个结构性问题：

1. **分片是哈希桶，不是业务键**：同一分片内不同 invId 的件会互相串行——哈希撞片时，A 奖品的秒杀扣减会排在 B 奖品的后面。并行度上限被分片数钉死，而不是随热点行数自伸缩。
2. **等待语义分裂**：点赞/库存走「等结果」的提交语义（commit），浏览量走 fire-and-forget（允许丢、`droppedViews` 计数说话）。两套模型、两套指标、两套降级心智。

经与数据库内核实现逐层对齐（三层结构：每表一张哈希表 → 每行一个 Queue → 行锁；惰性创建；leader 持锁吸收 follower；提交即清空；无请求即乐观删除），确认内核形态就是 per-invId 动态队列。本 spec 把引擎改造成同一形态，并按用户拍板**统一等待语义**：浏览量也改为「等结果」，与点赞/库存共用同一套引擎。

## Solution

**per-invId leader 模型引擎**：以 `invId`（业务行主键）为键的动态 Group 表替代固定分片数组——

- 第一条到达的请求**惰性创建**该 invId 的 Group 并成为 leader，在收集窗口内吸收 follower（窗口参数保留，即内核 `hotspot_update_max_wait_time` 的对应物）；
- 到点/攒满后 leader 带全队**一次落库**（一组一个事务不变），提交即清空、锁内提前释放领导权——下一批的 leader 由下一个到达者担任，收集与落库流水线重叠（内核的双执行单元乒乓）；
- Group 空闲即从表中乐观删除；删除竞态由 DB 行锁 + 唯一键自愈兜底；
- **浏览类从 fire-and-forget 翻转为 commit 语义**：等待落库终态，超时 = 结果未知（TIMEOUT → RETRY_LATER → 重放），不再有「允许丢」与 `droppedViews` 指标。

已知代价（用户知情拍板，非缺陷）：中低流量下事务数相比窗口模型上升（窗口是「用延迟换更少事务」的杠杆，leader 模型的事务频率恒为 1/单次写时长）；浏览请求 RT 增加窗口 + 落库时长；浏览类每请求分配 future 的成本回归（旧设计为省 future 专门做过优化，本次有意放弃）。

## User Stories

1. As a 点赞用户, I want 我的点赞请求在落库完成后才返回, so that 我看到的结果与数据库真实状态一致
2. As a 点赞用户, I want 同一帖子的高并发点赞被合并成一次落库, so that 热帖的计数写入不因行锁排队而超时
3. As a 浏览用户, I want 我的浏览被可靠记录并等待确认, so that 浏览量不因引擎降级而悄悄丢失
4. As a 内容创作者, I want 浏览量与点赞量一样是「要么落了要么没落」的提交语义, so that 计数对账没有「允许丢」的灰色地带
5. As a 秒杀用户, I want 我的库存预扣按 invId 排队合并, so that 结果精确反映我这一批的扣减事实
6. As a 运营者, I want 两个不同热销奖品的扣减互不排队, so that 多热点同时开抢时吞吐按热点数自伸缩
7. As a 领域服务开发者, I want 引擎对外 API（commit / awaitIdle / pending / close）保持不变, so that 调用方代码零改动
8. As a 领域服务开发者, I want 浏览写入与点赞写入走同一套提交语义, so that 不再为两类写维护两套等待模型与降级逻辑
9. As a 引擎维护者, I want Group 对象惰性创建、空闲即回收, so that 长尾 invId 不产生常驻内存开销
10. As a 引擎维护者, I want 双层并发控制（map 乐观探测 → Group 内部锁）, so that 高并发入队不挤在一张全局锁上
11. As a 引擎维护者, I want leader 提交开始前就释放领导权, so that 收集与落库两段流水线重叠、leader 的请求不被后续流量拖住
12. As a 引擎维护者, I want follower 等待超时返回前自动接班清队, so that 不产生无人认领的孤儿批
13. As a 引擎维护者, I want 单批上限（maxBatch）保留, so that 单条多行 SQL 的尺寸有界
14. As a 运维, I want 停机三步（停收 → 排空 → 残余回填 FAILED）, so that 滚动发布不丢已受理的写
15. As a 运维, I want 缓冲满即拒 + 调用线程就地落库组大小 1, so that 背压时不丢件也不扩容
16. As a 运维, I want 引擎终态保持 COMPLETED / TIMEOUT / FAILED 三态, so that 上层 RETRY_LATER 重放语义完全不变
17. As a DBA, I want 同一 invId 的落库保持入队顺序, so that 台账守卫式 UPDATE 的语义可推理
18. As a DBA, I want 同一 invId 任一时刻至多一条 flush 在途, so that 行锁竞争收敛在同一行内部
19. As a 测试, I want 引擎纯内存可测（不起 Spring、不连库）, so that 并发不变量测试秒级跑完
20. As a 测试, I want 浏览类集成测试断言从「允许丢」翻转为「必须全落」, so that 语义变更被测试钉住
21. As a 运维, I want 合并比（flushed ÷ batches）可观测并新增活跃 Group 数, so that 窗口/批大小调参有依据
22. As a 领域服务开发者, I want 预扣与确认仍是两个引擎实例, so that 两类失败域不共享缓冲
23. As a 秒杀用户（重复提交）, I want 同 bizId 的重放被唯一键吸收, so that 超时重试不双扣
24. As a 引擎维护者, I want 浏览幂等沿用明细唯一键 + INSERT IGNORE 影响行数口径, so that 统一语义后不引入新的去重机制
25. As a 运维, I want 落库异常/漏回填整组回滚补 FAILED 的兜底不变, so that 调用方不会悬到超时

## Implementation Decisions

- **引擎核心原地重写**：`MergeEngine` 类名、构造签名、公开 API（commit / awaitIdle / pending / close）全部不变，调用方（库存写入组件）零改动。内部以 `ConcurrentHashMap<String, Group>` 替代分片数组；Group = 入队缓冲 + 内部锁 + 条件变量 + 领导权标志。
- **领导权协议**（内核同构）：到达 → 锁内入队 → 领导权空缺则自任 leader 并在收集窗口内吸收 follower（攒满 maxBatch 或停机提前唤醒）→ 锁内摘批（≤maxBatch）→ **锁内先释放领导权再出锁** → 锁外调落库函数（一组一个事务）→ 逐件回填 → 队列空则条件 remove 出 map（乐观删除，竞态自愈由 DB 行锁 + 唯一键兜）；队列非空则 signalAll，等待者按协议接班。
- **等待者协议**：follower 在 Group 条件变量上带截止时间 await，循环检查三件事——我的件已终态（返回）/ 领导权空缺（接班成为新 leader）/ 超时（走 TIMEOUT）。
- **超时收尾**：follower 等超时、准备返回 TIMEOUT 之前，若领导权空缺则自任 leader 清一次队——把自己的 TIMEOUT 收敛为 COMPLETED，杜绝孤儿批。
- **MergeConfig 语义调整**：`maxFlushDelayMillis` 语义变为「leader 收集窗口」（内核 `hotspot_update_max_wait_time` 对应物，默认沿用现值）；`shardCount` / `shardOf` 退役（保留 yml 键一个版本兼容，不再参与路由）；`maxBatch` / `waitTimeoutMs` / 容量 / 排空超时语义不变。
- **装配层校验调整**：删除「shardCount ≤ 连接池 − 2」校验（后台线程模型消失，flush 并发 = 并发热点 leader 数，天然受连接池约束）；校验收敛为窗口/等待预算等自身约束。
- **浏览语义翻转**：浏览写入组件的异步入口退役，改为 commit 等结果；`droppedViews` 指标退役；浏览幂等沿用明细唯一键 + INSERT IGNORE 影响行数口径（不新增去重机制）；「允许丢」分支与不回补注释一并清除。
- **计数合并器**：8 分片常驻线程删除，点赞/取消赞/评论赞/浏览统一走新引擎；按动作分发落库的出口组件不变；「按影响行数计数」的守恒口径不变。
- **库存**：预扣/确认仍各一个引擎实例（失败域不同不共享缓冲）；守卫式台账 UPDATE 与「探一次再切」逻辑不动。
- **Metrics**：合并比快照口径保留；新增活跃 Group 数与接班次数可观测。
- **停机**：close() 三步保留——停收 → awaitIdle（等在途 leader 与接班链排空）→ 残余回填 FAILED；无后台线程可 join。
- **不变的兜底**：全部写路径凭唯一键幂等 + 对账 job；TIMEOUT = 结果未知 → RETRY_LATER → 重放被唯一键吸收；落库函数三条约定（逐件结果、抛异常整组回滚、不做重试）不变。

## Testing Decisions

- **不新增 seam**，沿用三个既有测试面（理想的 seam 数为零新增）：
  - **Seam 1（最高、纯内存）**：引擎单测 + 录制型落库桩。五条不变量（不丢件恰一终态、同 key 单飞、锁不跨 DB、停机排空、背压有界）与批上限/同 key 保序断言全部适配新模型；**新增断言**：不同 key 可并发 flush（撞片串行消除的钉子）、首件建队/窗口到点发车/攒满提前发车。
  - **Seam 2（组件层单测）**：计数写入组件与浏览写入组件的测试——浏览断言从「入队即回」翻转为「等终态」；`droppedViews` 相关断言删除。
  - **Seam 3（集成）**：库存守恒集成测试、帖子计数/商品浏览/跑腿浏览三个集成测试——计数守恒与浏览全落断言。
- 好测试的标准：只测外部行为（提交 → 终态、批上限、同 key 单飞与保序、停机排空、背压），不测内部结构（Group 对象、条件变量协议、map 删除时机）。
- 先例：引擎单测既有的并发骨架（闸门后并发起跑 + 虚拟线程后台提交）与录制型落库桩全部沿用。

## Out of Scope

- 合并窗口 / 批大小的生产调优（等合并比 metrics 说话，参照基准：内核实测平均每组 5.7 follower）
- 内核 100μs 窗口的对齐实验（窗口默认沿用现值；内核窗口短是因为收集发生在行锁持有期内，本架构窗口在请求线程侧，无此约束）
- CLAUDE.md / docs / 测试基线注释的口径同步（归文档票）
- 分布式多实例下的全局 per-invId 语义（引擎是进程内的；多实例各行其是，DB 行锁兜底）
- 库存拆分 / Redis 预扣等替代路线

## Further Notes

- **内核对齐证据链**（讨论中逐层查证）：PolarDB-X 团队《如何支持淘宝库存热点更新》（三层结构、Queue 生命周期、乐观删除原文）、淘宝内核月报 2020/02《RDS 三节点企业版热点组提交》（Group 回收队列 status 计数器）、PolarDB《热点行性能优化》（两组乒乓、`hotspot_update_max_wait_time` 默认 100μs）。
- **关键数字参照**：内核实测平均每组约 5.7 follower——即合并比指标的行业基准值；内核窗口 100μs 的原因（收集发生在行锁持有期内，吞吐 = 1/持锁时长）在本架构不存在，窗口可调空间大。
- **用户拍板记录**：①「浏览量那个也要等，都用同一套」（2026-11-23）；②知情接受中低流量事务数上升与浏览 RT 增加；③此前「分片内组间并行」方案被此 spec 取代。
