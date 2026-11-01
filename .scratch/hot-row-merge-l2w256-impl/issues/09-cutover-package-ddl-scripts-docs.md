# 09: 切换包——DDL、三份脚本、8 步上线与文档同步

**Spec:** `.scratch/hot-row-merge-l2w256/spec.md` §5.3、§6.1–§6.5、§8.2；判据见 `acceptance.md` D1–D3

**What to build:** 把前八票的代码变化配上一份**可照做**的切换包：窗口内 10–20 分钟停机、两人（执行 + 复核）按 8 步走完，出问题时按 4 步回滚。窗口内**只做可逆动作**，所以回滚不需要反向 DDL；不可逆的收口全部推到 D9。

三份脚本 + 一条人工 SQL：`cutover-catchup.sql` 把在途 `state = 0` 明细按旧逻辑合完（**含 `type = 2`，否则切换后 `waiting` 永久高挂**）、`cutover-cleanup.sql` 做清桶前的收尾与差额记录、`rollback-warmup.sh` 供回滚重灌桶；孤儿预扣（`record_id IS NULL` 的 `type = 1` 行）只给人工 SQL，不建 job。

DDL 分两段：窗口内只 `ALTER TABLE … state SET DEFAULT 1`（让新代码不写 `state` 时落 1，回滚后旧代码显式写 0 也不会重合历史行），不删列不删表；D9 才删列删索引、删两张桶表（**白名单写表名**——同库还有 `points_bucket` / `draw_count_bucket`，那是用户资产）、删 xxl 注册。`schema.sql` 直接写最终形态（新装库走新 DDL），存量库等 D9 对齐，两者 7 天不一致是明知的代价。

文档同步：`inventory.md` 的「合并触发」「对账」两节整段重写；`lottery.md` 与 `marketing-services.md` 的 handler 6 → 4；`draw.md` 的对账窗口第 1 条改成「孤儿预扣，无自动读数，见 SQL」；`timeout.md` 的兜底任务描述；`CONTEXT.md` 的「库存配额」「预扣」「时间格合并」「奖品类型」四个词条；新 ADR 取代 0011 / 0012（历史记录不改）。

**Blocked by:** 03, 04, 05, 06, 07, 08

**Status:** resolved

- [x] 切换记录模板可用：`sql/cutover/cutover-record-template.md`——初值快照（台账三列逐 inv、`SUM(like_count)` vs `COUNT(post_like)`）、差额表、D3 观测各有固定粘贴位
- [x] 窗口内 DDL 只有一句 `state SET DEFAULT 1`（`window-ddl.sql`，两张明细表各一句 + `SHOW CREATE TABLE` 验证），**没删列没删表**
- [x] D9 收口 DDL 三步齐（`d9-finalize.sql`：删列与索引 / 白名单删两张桶表 / 删 xxl 注册，尾部带全部验证 SQL，明确标注不可逆）；`schema.sql` 已写最终形态（删两张桶表、明细表删 `bucket_id` / `state` / 两条索引 / CHECK），`sql.md` §2 与两处 docs README 同步
- [x] `cutover-catchup.sql` 覆盖 `type = 1` 与 `type = 2`（虚拟段同构），跑完三条读数全 0；一次性库演练验证台账数学（10→7/1/2 open、扣空→exhausted、不限量不动 surplus）与重跑幂等
- [x] `cutover-cleanup.sql`：outbox 收尾 + 逐 inv 生成「台账 surplus vs Σ桶余量」MGET 比对命令，差额**只记不修**进切换记录（没差额写 0）；清桶与 `--scan` 复跑为 0 的命令在脚本内注明
- [x] `rollback-warmup.sh` 能重灌桶（一次性库演练：7=3+2+2 三桶均分、0 量单桶、-1 跳过、重跑幂等、回读校验不平即非零退出；修掉 `docker exec` 缺 `-i` 丢 stdin 的真 bug）；回滚四步（停新 jar → 回滚 jar → 重灌桶 → 放开 xxl 注册）写进运行手册并逐条带验证
- [x] 回滚形态把 `inventoryMergeBackstop` **带回**（运行手册「回滚形态 ≠ 新形态」一节写明理由：回滚形态里消息丢 ⇒ `surplus` 不降 ⇒ 超卖；停用不删除就是为它留的），回滚治不了的三件事与触发条件一并写死
- [x] 上线顺序写死（运行手册 §0）：计数先切、库存后切、单位是进程；D0 切 bs → D1 晚高峰后抽样 → D2 切 marketing → D9 收口；不做「只让商品浏览先跑一周」
- [x] 第 7 步启动验证清单在运行手册里：健康检查 200、无 `Unknown column` / `Table doesn't exist`、handler 恰 4 个、≥2 分钟无「找不到执行器任务」、日志无 `PostCountAggregator` / `bs-inventory-merge`
- [x] 孤儿预扣人工 SQL 进运维手册（运行手册 §4 + `inventory.md`，两张明细表 LEFT JOIN `draw_record` 找 `record_id IS NULL` 的 `type = 1` 行），**不建 job、不建巡检**
- [x] 六处文档 + ADR 0013 全部落地：`inventory.md`（整篇换成进程内合并形态，触发 / 合并 / 兑换确认 / 预热 / Redis key / 对账按新现实重写）、`lottery.md`（job 6 → 4、`bs-inventory-merge` 事件行删、确认改同步、4312 理由更新）、`draw.md`（对账窗口第 1 条改孤儿预扣见 SQL、事务边界与 SHIP 行同步）、`backend/docs/architecture/marketing-services.md`（4 handler、18 张表、cutover 目录入表）、`backend/marketing/docs/architecture/timeout.md`（两个库存 job 都已删）、`CONTEXT.md`（「库存配额」「预扣」「奖品类型」改写，「时间格合并」整条删除，「超时任务」边界句与「抽奖次数桶」Avoid 句同步）；ADR 0013 已随 spec 定稿落地（a5b23db），历史 0011 / 0012 未动
- [x] `inventory-l2w256/` 参考件保持原样，零改动