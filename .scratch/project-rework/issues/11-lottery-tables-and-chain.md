# 票 11 · 抽奖 A：draw_* 6 张表落地 + 责任链骨架 + 表预热

Type: task
Status: resolved
Blocked by: 08

## 上下文预算（硬约束）

| 项 | 值 |
| --- | --- |
| 目标 | **150–200k**；硬顶 **300k** |
| 预估改文件 | ~40（6 张表五层 + 责任链 6 个类 + 工厂 + 表预热 + `CacheWarmupJob` + 测试 4~6 + 文档 2） |
| 必读 | **`sql.md#L729-L844`（仅此区间，约 116 行）**；rafflex 的 `domain/.../raffle/**` + `CacheWarmupJob` + `LuaScriptManager`（见下）；本票 |
| 不读 | `sql.md` 其余段落；旧 `lottery_*` 业务代码（票 13 才推倒重写）；库存段（票 12） |
| 必跑 | `backend/marketing`：新表 + 责任链 + 预热用例；旧 `Lottery*` 用例仍须绿 |
| 越界信号 | 想「顺手把抽奖主链路接起来」→ 停手，那是票 13 |

## 已核实事实

1. `sql.md` 抽奖段 = **L729–L844**，共 **6 张表**：`draw_activity`(729) / `draw_strategy`(748) / `draw_strategy_award`(763) / `strategy_weight`(786) / `draw_record`(803) / `draw_record_detail`(828)。
2. `sql.md` 自述：抽奖/库存部分**是目标设计稿、尚未落库**；当前实现是 `lottery_*`。故本票是「新增表 + 新增骨架」，旧 `lottery_*` 与旧代码**本票不动**（票 14 才换）。
3. **`strategy_weight` 在 `sql.md` 里是旧格式，需要按文档自定的命名与主键口径规范化**——发现即回写 `sql.md`（`map.md` 已授权「票内定并回写」）。
4. `draw_record_detail` 语义：只插入。创建 `draw_record` 时同事务落 `DRAW`，此后 `state` 每推进一步落一条（票 14 发货状态机用）。
5. rafflex 参考（**抄骨架、不抄库存**）：
   - 责任链接口与节点：`rafflex-backend/domain/src/main/java/cn/talent/domain/raffle/service/raffle/chain/{IChainNode,AbstractChainNode,factory/ChainFactory,node/{BlacklistChainNode,RuleWeightChainNode,DefaultChainNode}}`
   - 抽奖服务骨架：`.../raffle/service/raffle/{IActivityRaffleService,AbstractActivityRaffle}`
   - 策略/奖品/权重 DAO：`rafflex-backend/infrastructure/.../dao/{IStrategyDao,IStrategyAwardDao,IStrategyBlacklistDao,IStrategyWeightDao}`
   - **表预热**：`rafflex-backend/trigger/src/main/java/cn/talent/trigger/job/CacheWarmupJob.java`（概率 **×10000** 入 Redis）
   - **Lua 管理**：`rafflex-backend/infrastructure/src/main/java/cn/talent/infrastructure/config/LuaScriptManager.java`
6. 现状消费组/端口等标识已在票 06/07 收敛，本票不涉及。
7. why 预热放在本票：预热的是**链的配置数据**（概率表 / 权重），与责任链同属「决策侧」；主链路（票 13）只负责用它。放在这里能让票 13 专做编排，是给它减负的一刀。

## Question

在 `backend/marketing` 的 lottery 域落地 `sql.md` 的 **6 张 draw_* 表**及其五层代码，并把 rafflex 的**责任链**骨架搬过来按本仓分层重写：

- `ChainFactory`：按 DB 配置**动态组链**（黑名单 → 权重保底 → 默认），链节点可插拔。
- 三个节点：黑名单拦截（命中即拒绝/降级，按 `sql.md` 表语义）、权重保底（`strategy_weight` + `draw_strategy_award` 概率口径）、默认兜底。
- **表预热**：把概率 / 权重按 ×10000 口径灌入 Redis（Redisson）+ `CacheWarmupJob`（启动预热 + 配置变更后重预热）；提供「预热态 / 降级态」的可查状态，降级走 DB 直算的入口留在票 13 验。
- 表结构落 `backend/marketing/sql/schema.sql`；DAO/PO/mapper/repository/entity/valobj 齐备。
- 本票**不接主链路**（不扣次数、不扣库存、不写 `draw_record`），只保证骨架可单测、预热可独立触发。

## 施工边界（不做什么）

- 不实现抽奖主链路（不扣次数、不扣库存、不写 `draw_record`）→ 票 13。
- 不做库存分桶 / 合并任务（→ 票 12）。
- 不删旧 `lottery_*` 表与旧代码（→ 票 13）。
- 不改 outbox（票 08 定型）。

## 验收

1. `backend/marketing && mvn -o test` 全量绿（旧 `Lottery*` 用例不回归）。
2. 责任链用例 ≥3：黑名单命中短路；权重按配置命中指定奖品档；无命中走默认兜底；`ChainFactory` 按 DB 配置改链顺序后行为随之变。
3. 预热用例 ≥2：预热后 Redis 概率表数值与 DB 配置符（×10000 口径）；配置变更后重预热生效。
4. `schema.sql` 收录本票 6 张表 + 比对脚本逐表一致；`schema-legacy.sql` 保持不动并注明「`lottery_*` 待票 13 删」。
5. `sql.md` 回写：`strategy_weight` 规范化结果（若改了）。
6. `## Answer` 记录：链顺序与短路语义、权重/保底算法口径、`draw_record_detail` 的 action 枚举定义、**Redis key 命名与预热结构**（票 13 直接引用）。

## Answer

**落位**：新域段 `raffle`（`types.raffle` / `domain.raffle` / `infrastructure.raffle` / `trigger.raffle`），
与旧 `lottery` 域代码并存不同包、互不引用；@MapperScan 已加 `infrastructure.raffle.dao`（FQCN 命名器照旧）。
链节点 bean 名 = 节点码（`rule_blacklist` / `rule_weight` / `rule_default`，与 `draw_strategy.rule` 分段值一致）。
六张表五层齐备：`IRaffleDao` + 6 PO + `RaffleMapper.xml` + `IRaffleConfigRepository`/`IDrawRecordRepository`（实现
`RaffleConfigRepository`/`DrawRecordRepository`）+ entity/valobj/aggregate。`draw_record`/`draw_record_detail` 只备五层
（`DrawRecordAggregate` 同事务落 record+DRAW 明细），无调用方——主链路归票 13。

**链顺序与短路语义**：按 `draw_strategy.rule` 节点码序列组链，链尾恒补默认节点；未配 rule = 只有默认节点。
黑名单命中（Redis bucket 存在，key 口径见下）→ 强制出 `draw_strategy_award(type=blacklist)` 奖品位并**短路**；
配了黑名单用户但没配 blacklist 奖品位 → 放行下一节点（不把人卡死）。权重命中 → 该档池出奖并**短路**；
未达任何档或该档概率表缺失（降级）→ 透传默认节点。默认节点概率表缺失 = 未预热 → 抛
`RAFFLE_WARMUP_MISSING("4311")`（`types.common.ResponseCode`，DB 直算降级入口票 13 接并验）。
`ChainAwardVO{awardId, logicModel(RaffleRuleNodeVO), weightCount}` 为单抽结果；链缓存按 strategyId，
`RaffleChainFactory.evict(strategyId)` 失效，预热服务重预热时自动调（配置变更 → 行为随之变，有用例）。

**权重/保底算法口径**：累计抽数 = `draw_record` 中该 user_id + strategy_id 的行数（`IRaffleConfigRepository.queryUserDrawCount`）；
命中档 = 「count ≤ 累计抽数」各档中 count 最大的一档；无档命中 → 下一节点。档内概率表（预热建）按该档
weights 引用奖品位的 `award_rate` **池内归一化**（base = rate×10000/Σrate，最大余数法补齐到 10000 槽）——
权重是独立保底池，不改全局概率。默认表 = type=normal 奖品位 `award_rate×10000` 直落槽（合计恒 1.0000，预加热校验，
不符抛 CONFIG_PARAM_ERROR）。THANKS 占位 type=normal、rate 计入概率池（照 sql.md 列注释）。

**`draw_record_detail` action 枚举**（`DrawRecordActionVO`，type 值 → 推进后 state）：
`DRAW("DRAW","待兑换")`（落 record 同事务落一条，票 13）/ `EXCHANGE("EXCHANGE","已兑换")` /
`SHIP("SHIP","已发货")` / `COMPLETE("COMPLETE","已完成")`；uk_record_type 幂等，state 推进重试不重复落。

**Redis key 命名与预热结构**（前缀 `bs:raffle:`，见 `types.raffle.Constants`）：
- 概率表 `bs:raffle:table:{strategyId}:default` 与 `bs:raffle:table:{strategyId}:weight:{count}`：
  RMap<Integer 槽位 0..9999, Long awardId>，恒 10000 槽；预热**先删后写**整体重建（无陈旧槽位）。
- 特型奖品位 `bs:raffle:award:{strategyId}:blacklist` / `:fallback`：bucket 值 = awardId
  （各取 type 同名行中 sort,id 最小的一条；缓存未命中回查 DB）。
- 黑名单成员 `bs:raffle:blacklist:user:{strategyId}:{userId}`：bucket 存在即命中；**本域无黑名单用户表，
  该集合由风控侧写入，预热不负责**（用例自造）。
- 预热态 `bs:raffle:warmup:{strategyId}`：bucket 值 = 预热完成时刻；不存在 = 降级态
  （`IRaffleWarmupService.isWarmed` 可查）。预热入口 `warmupByActivityId`（活动→策略）/`warmupByStrategyId`，
  xxl handler `raffleCacheWarmup`（参数 = activityId），重预热幂等且顺带失效链缓存。
- 概率表值为 Redisson 默认 codec（RMap<Long>），bucket 恒 StringCodec——读侧（票 13）注意 codec 对齐。

**sql.md 回写（两处，cmp_schema_marketing.py 22 表逐列全等）**：
1. `strategy_weight` → `draw_strategy_weight`：表名并入 draw_* 家族；`bigint unsigned` 去掉 unsigned；
   create_time/update_time 去 DEFAULT CURRENT_TIMESTAMP / ON UPDATE（应用写入口径）；补全列/表 COMMENT；
   约束名 `unique_strategy_id_count_level`→`uk_strategy_id_count`、`index_strategy_id_count_weights`→`idx_strategy_id_count_weights`；
   weights varchar(32)→varchar(256)（32 容不下两条 snowflake award_id）。schema.sql 头部「唯一例外」注释同步消除。
2. `draw_record.award_decr` 纠 typo 为 `award_desc`（列注释不变）。

**验收对照**：全量 `mvn -o test` 85 绿（78 旧含全部 `Lottery*` 用例零回归 + 7 新）；责任链 5 用例
（黑名单短路 / 权重命中指定档（含 5↔10 档升降与无档落默认）/ 默认兜底 / 工厂按 DB 配置改链行为随变 / 未预热抛 4311）；
预热 2 用例（默认表 + 权重表 + 特型缓存与 DB 配置符 / 按活动预热 + 概率重配撤奖位后重预热生效且 isWarmed 先 false 后 true）；
`schema-legacy.sql` 一字未动（头部本就注明 lottery_* 票 13 删）。
文档：`backend/marketing/docs/db/draw.md`（新）+ docs/README.md 索引行 + `CONTEXT.md` 新增「抽奖决策侧」词条
（责任链 / 权重档 / 表预热）+ map.md 票 11 收口段。

<!-- Context used: ~110k；未触发拆分；连带影响：MybatisConfig 加域扫描、ResponseCode 加 4311、
     sql.md 两处回写（见上）、docs/README.md 索引、CONTEXT.md 词条、map.md 收口段 -->
