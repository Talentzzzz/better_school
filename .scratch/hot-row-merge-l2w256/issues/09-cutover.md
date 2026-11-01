# 09 · 迁移与回滚：开关、切流顺序、在途收口

Type: grilling
Status: resolved
Blocked by: 05, 07, 08

## Question

这是「改完就回不去」的那一类改动（表里少几列、几条边整段消失、Redis key 不再写）。要定的是**怎么切、怎么退**：

1. **要不要开关**：一个 `merge.enabled` 配置让新旧链路并存（旧路径保留代码），还是一个 PR 换掉、不留双跑？双跑的好处是回滚一次配置；坏处是两条路都能写同一行，测试要覆盖 2×2。**ADR-0011/0012 的先例是「不双跑」**（新旧逻辑并存会让「这条消息该合哪格」有两种答案）。这次同不同意沿用这个立场？
2. **切流顺序**：库存（资金敏感）先切还是后切；计数（近似）先切还是后切。要不要先在一个低流量段（例如商品浏览量）跑满一周再动库存？
3. **存量收口**：切换瞬间 Redis 桶里还有余量、明细表里还有 `state = 0` 的行、outbox 里还有未发出的合并消息。三样各自怎么处理（清桶以台账为准 / 先把 `state=0` 合完再切 / 停消费后等 outbox 排空）。
4. **回滚**：假设上线 2 小时后发现超卖。回滚 = 回滚 jar + 重建 Redis 桶（从台账 `surplus` 重灌）+ 重新注册 xxl 任务？这套动作要写成一页 checklist，还是要做到「回滚只需回滚镜像」？
5. **发布顺序（滚动窗口）**：如果有旧实例在读旧表结构，`bucket_id` 删列会直接让旧实例报错。这个工程的发布形态是单实例停机发布吗（`scripts/run-app.sh`）？如果是，删列可以和后端代码同一批；不是的话要先留列。

结论要写成一段「上线 8 步」，每步可验证。

## Comments

- 票 05 已定：要删的是桶两表、`redis_qty` 列、Lua、去重 key、预热 job、`bs-inventory-merge` 与消费者；切换以**台账 `surplus` 为准**，桶余量清空并记一条差额流水，`state = 0` 的未合明细先由旧逻辑合完。
- 因此本票收窄为：**要不要开关（双跑）**、**计数与库存的切流先后**、**发布顺序（单实例停机发布还是留列灰度）**、**回滚 checklist**。第 5 问的「旧实例读旧表结构」在单实例停机发布下可以不考虑，但要确认 `scripts/run-app.sh` 的部署方式确实没有灰度并存的实例。
- **票 08 已定，转来两条**：① xxl 侧不用「回滚时重新注册」，而是**先重灌 seed 删注册再发版**（一条 `DELETE ... IN ('inventoryMergeBackstop','inventoryBucketWarmup')`，验证 SQL 见票 08 §5）——这一步要落进本票的上线 8 步，并且要写清「回滚只回滚 jar 时，这两个注册不会自己回来，也没什么需要回来的：新表结构下旧代码跑不起来」。② 兜底 job 已整条退休、不建替代 job，所以**回滚 checklist 里不再有「重建 xxl 兜底任务」这一项**；反过来，若上线后发现账不平，可用的手段只有死信队列、`draw_record` 停在待发货、与票 08 §2 那条孤儿预扣 SQL（人工）。

## Answer

（2026-10-22 定）

**一句话：不设开关、一个 PR 换掉；计数（bs）先切、库存（marketing）后切，切流单位是进程不是功能；切流窗口里只做「可逆的那几样」，删列 / 删表 / 删注册推到 D9 收口——于是回滚 = 回滚 jar + 重灌桶 + 放开注册，不需要反向 DDL，也不需要恢复备份。**

### 0. 先钉五条事实（后面全靠它们）

| 事实 | 出处 | 对本票的影响 |
| --- | --- | --- |
| **单实例停机发布** | `scripts/run-app.sh` 是 `exec java -jar` 单进程（bs 8080 / marketing 8081）；`docker-compose.yml` 只有中间件（nacos / mysql / redis / rocketmq / xxl / minio / es），没有任何应用服务；无 k8s、无网关、无灰度 | 「旧实例读旧表结构报错」这个滚动发布问题**不存在**；删列与代码同批是允许的（但见 §4，不该这么做） |
| **两个工程两次发布** | 两个 jar 各起各的；计数表在 `better_school`、库存表在 `bs_marketing`，两边不交叉 | 「切流顺序」的粒度是**进程**，不是功能 |
| **计数侧有事实源、浏览没有** | `post_like` / `post_comment_like` 是关系行；`post.view_count` / `product.view_count` 只有自己 | 帖子点赞的异步滞后可以在切换点一次性抹平；浏览量丢即丢 |
| **评论点赞本来就同事务** | `CommentLikeCase:13`「唯一键幂等 + `like_count` 同事务 ±1」 | 它不需要重算，只需断言；本次改的只是「逐笔写」→「合并批写」 |
| **旧逻辑在停机后不会自己跑** | 60s 兜底是 xxl handler（随进程停），MQ 消费也是；单实例又没有闸门能「只停流不停进程」 | 票 05 那句「`state = 0` 先由旧逻辑合完」在单实例里**没有可执行的中间态**，必须固化成一次性 SQL（§3a） |

### 1. 不要开关（Q1）

**同意沿用 ADR-0011 / 0012 的「不双跑」，但这次的理由不是立场，是物理上做不到。**

- ADR-0011 的原话是「回滚要把这些逐项重建，不是配置开关」；ADR-0012 是「不双跑——新旧逻辑并存会让『这条消息该合哪格』有两种答案」。两句都还成立。
- 这次更强：**开关只能切代码，切不了数据形态**。旧路径的每一步都踩在将被删掉的东西上——`state` / `bucket_id` 列、两张 `*_bucket` 表、`bs:inventory:*`、`inventoryMergeBackstop`。而且这个开关**从头到尾没有可用区间**：D9 收口之后 `false` 读到的是没有 `state`、没有桶表的 schema，启动即报错；D9 之前（观察期里）旧代码虽然能起来，但桶已被清空且新代码不再回填，第一笔预扣就「售罄」。
- 2×2 的代价也被重新定价：以前是「两套逻辑写同一行」，现在是「两套逻辑写两套形态」。新逻辑**不维护 Redis 桶**，所以双跑期间桶只会越来越旧，回滚时无论如何要重灌（`inventoryBucketWarmup` 的 javadoc 自己写着「SET 语义会把在途预扣灌回去」）。**双跑买不到「一次配置回滚」**，只买到一段「两种答案并存」的时间。

结论：一个 PR、一条路、没有开关。回滚靠 §4 那份清单，不靠配置。

### 2. 切流顺序（Q2）

**先纠一个前提：计数侧不是「近似」。** 票 02 已把点赞定成与关系行同事务的**精确值**，只有浏览量是近似、允许丢。所以「先切计数」的理由**不是「算错了也无所谓」**，而是三条更硬的：

1. **错得起但能当场发现**：点赞有事实源（`post_like` 条数），切换后一条 SQL 就能证伪；库存没有第二个真相源。
2. **它是同一套引擎形状的第一次真跑**：等 future（5ms 窗口）、停机排空、背压降级日志、`drainFailed` 读数——这些在计数域上出事只影响一个数字。
3. **它是全流程里唯一一次真正「只需回滚镜像」的窗口**：bs 不动 DDL、不动 Redis、不动 xxl，回滚 = 回滚 jar + 重算一次点赞。

**库存后切**：钱 + 不可逆动作（清桶）+ 需要台账对账，回滚成本最高，让它成为最后一个动的。

**「先让商品浏览量跑一周」这个选项不存在**：商品浏览（`ProductCountConsumer`，现在是每条消息一次直更 `product.view_count`）与帖子点赞在**同一个 jar、同一个进程、同一份引擎**里。要让浏览先跑一周，就得在同一个 jar 里保留两条写路径——那正是 §1 否掉的双跑。**最小可切单元是 bs 进程**。

**间隔不是一周，是「一个日间高峰 + 一次抽样对账」**（校园墙的高峰在 20:00–23:00）：

- 多等一周不会让库存那步更安全——风险在破坏性迁移与在途收口，不在流量积累；
- 跑过一个高峰能暴露真正想暴露的东西：`BUFFER_FULL` 降级、`drainFailed`、慢 SQL、背压是否被触发。

排期（D0 = bs 切流日）：**D0 切 bs → D1 晚高峰后抽样（§5 第 8 步）→ D2 切 marketing → D9 收口（§6，库存切流后整 7 天；后文一律写 D9）**。

### 3. 存量收口：三样东西各自的处置（Q3）

#### (a) `state = 0` 明细：停机后用一次性 SQL 合完，不等兜底

单实例下没有「先关写入口、等 60 秒兜底合完、再停进程」这个中间态：写入口就是进程本身。所以把旧逻辑的合完规则（`mergeCell` 的加减方向，已核对 `PhysicalQuotaMapper.settleQuota` / `transferWaitingToIssued` 与 `VirtualInventoryQuotaMapper.settleInventory`）固化成 `marketing/sql/cutover/cutover-catchup.sql`，在**停机后、DDL 前**执行：

```sql
-- ① 实物段：先 type = 1（进 waiting），再 type = 2（waiting → issued）——顺序即旧 mergeCell 的顺序
UPDATE physical_marketing_quota q
  JOIN (SELECT inv_id, SUM(quantity) s FROM physical_marketing_quota_detail
         WHERE state = 0 AND type = 1 GROUP BY inv_id) d ON d.inv_id = q.inv_id
   SET q.state = IF(q.surplus_qty >= 0 AND q.surplus_qty = d.s AND q.state = 'open', 'exhausted', q.state),
       q.waiting_qty = q.waiting_qty + d.s,
       q.surplus_qty = IF(q.surplus_qty < 0, q.surplus_qty, q.surplus_qty - d.s),
       q.update_time = NOW();

-- ② 断言（必须 0 行；非 0 停手：说明 waiting 不足以承接确认 = 不变量破坏，交人工）
SELECT q.inv_id, q.waiting_qty, d.s FROM physical_marketing_quota q
  JOIN (SELECT inv_id, SUM(quantity) s FROM physical_marketing_quota_detail
         WHERE state = 0 AND type = 2 GROUP BY inv_id) d ON d.inv_id = q.inv_id
 WHERE q.waiting_qty < d.s;

UPDATE physical_marketing_quota q
  JOIN (SELECT inv_id, SUM(quantity) s FROM physical_marketing_quota_detail
         WHERE state = 0 AND type = 2 GROUP BY inv_id) d ON d.inv_id = q.inv_id
   SET q.waiting_qty = q.waiting_qty - d.s,
       q.issued_qty  = q.issued_qty  + d.s,
       q.update_time = NOW();

-- ③ 虚拟段：只有 type = 1，直接进 issued
UPDATE virtual_inventory_quota q
  JOIN (SELECT inv_id, SUM(quantity) s FROM virtual_inventory_quota_detail
         WHERE state = 0 GROUP BY inv_id) d ON d.inv_id = q.inv_id
   SET q.state = IF(q.surplus_qty >= 0 AND q.surplus_qty = d.s AND q.state = 'open', 'exhausted', q.state),
       q.issued_qty  = q.issued_qty  + d.s,
       q.surplus_qty = IF(q.surplus_qty < 0, q.surplus_qty, q.surplus_qty - d.s),
       q.update_time = NOW();

-- ④ 封口
UPDATE physical_marketing_quota_detail SET state = 1, update_time = NOW() WHERE state = 0;
UPDATE virtual_inventory_quota_detail  SET state = 1, update_time = NOW() WHERE state = 0;
```

**这不是改票 05**：票 05 要的是「先合完再切」（否则 `waiting` 永久高挂），票 07 §7 也把它写成硬约束；本票只改「谁来合」——从「旧兜底 job」换成「一段等价的一次性 SQL」，因为在单实例里没有可停流不停进程的中间态。`SET` 的左到右取值顺序与 mapper 注释一致（`state` 的 IF 必须排在 `surplus_qty` 赋值之前）。

**验证**：两条 `SELECT COUNT(*) … WHERE state = 0` 都为 0；`SELECT COUNT(*) FROM physical_marketing_quota WHERE waiting_qty < 0` 为 0。

#### (b) Redis 桶：先合完、再对账、最后清——顺序不能反

桶是**唯一**能暴露「Redis 扣了、明细没落」的参照物（ADR-0011 列的第 2 类丢失）。合完之后等式 `Σ 桶余量 == 台账 surplus` 才成立；差额就是真实泄漏，**只记录、不修数**：

```bash
# 逐个 inv（只对 surplus_qty >= 0 的行；-1 不限量没有桶等式）
redis-cli --scan --pattern "bs:inventory:bucket:quota:${inv}:*" | xargs -r redis-cli --raw GET | awk '{s+=$1} END{print s}'
# 与 SELECT surplus_qty FROM physical_marketing_quota WHERE inv_id = ${inv} 比对；虚拟段同理（bucket:virtual:*）
```

- **差额流水落到哪**：仓库里没有出入库流水表（旧的已删，`CONTEXT.md` 有记），为一行差额重建一张表不划算。差额 = 第 1 步初值快照文件里追加一段 `inv_id / 台账 surplus / 桶合计 / 差额`，进发布记录，并在新 ADR 里写一句「切换点差额 = X，以台账为准」。
- 清桶：`redis-cli --scan --pattern 'bs:inventory:*' | xargs -r redis-cli DEL`（桶 key 与 `bs:inventory:merge:*` 去重 key 一次清完）。
- **验证**：再扫一遍为 0；且此后 `bs:inventory:*` 在新 jar 的运行期不再出现（新代码不写这两类 key）。

#### (c) outbox 未发消息：按 topic 处置，不等排空

| topic | 处置 | 理由 |
| --- | --- | --- |
| `bs-inventory-merge` | 与 `state = 0` 一起「合完 → 删行」 | 它是**唤醒**不是数据；`state = 0` 清零后这些消息指向的格已不存在。必删的真正理由是消费者即将消失，留着会被共享的 `outboxScan` 发往无人消费的 topic，污染积压/死信观测 |
| `bs-post-count` / `bs-product-count` | 重算后直接删行 | 点赞已由重算覆盖（关系行才是真相），浏览本来就允许丢 |

```sql
DELETE FROM outbox_message WHERE destination IN ('bs-post-count', 'bs-product-count');  -- better_school
DELETE FROM outbox_message WHERE destination = 'bs-inventory-merge';                    -- bs_marketing
```

**为什么不等排空**：等排空只能在进程还活着时做，而进程活着就不能做 DDL；为了几十秒的队列尾巴把整个收口顺序倒过来不值。等排空唯一真实的好处（点赞增量不丢）已被重算覆盖。

**验证**：`SELECT COUNT(*) FROM outbox_message WHERE destination IN (…) AND state = 0` 为 0；新 jar 启动日志里不应出现 `bs-post-count-group` / `bs-product-count-group` / `bs-marketing-inventory-merge-group` 三个消费组（订阅没了，broker 上的存量自然过期，不必清 topic）。

### 4. 不可逆点在哪、回滚怎么退（Q4 + Q5）

票面把「删列同批」当成一个发布形态问题；它其实是**回滚预算**问题。三条候选不可逆动作里，只有一条必须当场付：

| 动作 | 不可逆吗 | 处置 |
| --- | --- | --- |
| 清 `bs:inventory:*` | 是（桶里只有余量，没有历史） | **当场做**。但它是唯一可重建的：从台账 `surplus` 重灌，秒级 |
| 删 `bucket_id` / `state` 列 + 两张 `*_bucket` 表 | 是 | **推到 D9 收口**（§6）。切流窗口只做一条 `ALTER … SET DEFAULT 1`（元数据级、可逆） |
| 删两行 xxl 注册 | 是（票 08 的 `DELETE`） | **切流窗口只停用**（`trigger_status = 0`），D9 再删。停用后 admin 不派单，同样不刷「找不到执行器任务」 |

于是：**切流窗口结束时，旧形态仍然完整可跑**——列还在、桶表还在、注册行还在（只是停用）。回滚变成四条：

1. **停新 jar**（优雅停机；缓冲排空由 `@PreDestroy close()` 承担，`drainFailed` 记数）；
2. **回滚 jar**（两个工程各自的上一版）；
3. **重灌桶**：按台账 `surplus_qty` 写回 Redis。不必依赖刚被停用的 `inventoryBucketWarmup`（那条路要恢复注册表、要 admin，变量更多）——直接一条 `redis-cli` 循环按 `SELECT inv_id, surplus_qty FROM {physical_marketing_quota|virtual_inventory_quota} WHERE surplus_qty >= 0` 写 key 更快；桶号按 `*_bucket` 里该 inv 的行数均分（全塞 `bucket_id = 0` 也能跑，只是热点变差）。回滚窗口里没有在途预扣，`SET` 语义安全；
4. **放开注册**：`UPDATE xxl_job_info SET trigger_status = 1 WHERE executor_handler IN ('inventoryMergeBackstop','inventoryBucketWarmup')`。

**这一条是对票 08 的修正，必须写明**：票 08 说「回滚只回滚 jar 时，这两个注册不会自己回来，也没什么需要回来的」——那句话成立于「只回滚 jar、表结构不回去」这个**已被本票否决**的形态。既然回滚包含「桶表与列都还在」，旧代码就是完整可跑的旧形态，那么 `inventoryMergeBackstop` **必须**一起回来：它治的是「合并消息整条丢了」，回滚后这一类丢失立刻重新存在（消息丢了 ⇒ `surplus` 不降 ⇒ 超卖）。新模型里它该退休，回滚形态里它不能缺席。

**回滚治不了的三件事**（写在清单第一行，避免有人以为回滚等于撤销）：

1. 已经暴露的账不平：`waiting` 不足抛进死信的那几条确认，回滚后同样确认不了，仍然人工（票 08 §2 的孤儿预扣 SQL 是另一个方向，不通用）；
2. 切换窗口丢掉的浏览量（无真相源，永远补不回来）；
3. 回滚后延迟剖面换了（缓冲等 future → Redis 预扣一刀），容量假设要重新看。

**回滚触发条件**（提前定，避免当场拍脑袋）：

- 抽奖接口 p99 超基线 3 倍，或 `TIMEOUT` 占比 > 1%（说明合并器在拖住 HTTP 线程）；
- 死信出现 `INVENTORY_PARAM_ERROR`（即票 07 §3 的不变量破坏）；
- 第 8 步抽样对账出现任一 `waiting + issued <> SUM(type = 1)` 的行。

顺便纠一个票面前提：「上线 2 小时后发现**超卖**」在新模型里不可能——`surplus >= N` 守卫与台账扣减在同一个事务里，超卖的结构性原因（两阶段 + Redis 只是余量面）已经不存在。真会触发回滚的是上面三条。

**为什么不同批删列**（Q5 的直接回答）：发布形态允许（单实例、无灰度、`run-app.sh` 单进程），但**能同批 ≠ 应该同批**。同批唯一的好处是少一次在线 DDL（`DROP COLUMN` 在 MySQL 8 上是 INSTANT/INPLACE，本来就不需要停机窗口），代价是把最不可逆的一步提到切换窗口的第一分钟——而回滚的价值恰恰全在最初的 24 小时。留列的代价是观察期里 schema 多两列死列、两条不会被查询的索引、两张死表（新代码一行不读），这个代价明显更小。

### 5. 上线 8 步（每步可验证）

窗口内 **10–20 分钟停机**，两个人（执行 + 复核）。标【A】的步骤是 bs 批次（D0），标【B】的是 marketing 批次（D2），无标注两批都做。

**第 1 步 · 备份与初值快照**【A+B，不停机】

```bash
docker exec bs-mysql mysqldump -uroot -pbs123456 --single-transaction --routines better_school   > backup-bs-$(date +%F-%H%M).sql
docker exec bs-mysql mysqldump -uroot -pbs123456 --single-transaction --routines bs_marketing    > backup-mkt-$(date +%F-%H%M).sql
```

```sql
-- 初值快照（回滚与「守恒」的唯一凭据；写进发布记录文件）
SELECT inv_id, surplus_qty, waiting_qty, issued_qty FROM physical_marketing_quota ORDER BY inv_id;
SELECT inv_id, surplus_qty, issued_qty             FROM virtual_inventory_quota  ORDER BY inv_id;
SELECT (SELECT SUM(like_count) FROM post_like_count) AS 窄表合计, (SELECT COUNT(*) FROM post_like) AS 关系行数;
```

- **验证**：两个 dump 结尾都有 `Dump completed`；初值三个结果集粘进 `cutover-<日期>.md`；最后一行两个数此时**可以不等**（旧链路滞后），切换后必须相等。

**第 2 步 · 停流与优雅停机**【A+B】

- 有维护页就置维护页；然后向两个进程发 SIGTERM（控制台 Ctrl+C / `kill <pid>`），**不要 `taskkill /F`**——它会绕过 shutdown hook，`PostCountAggregator.shutdown()` 的最后一次 `flush()` 与合并器的「停收 → 排空 → 关池」都挂在 context close 上。
- **验证**：两进程退出码 0；日志有 `@PreDestroy` 收尾（计数侧 `post-count-flusher` 之后无新 flush）；`8080` / `8081` 不再监听；`SHOW PROCESSLIST` 只剩运维连接。

**第 3 步 · 在途收口**

- 【A】重算帖子点赞（旧链路必然滞后，关系表是真相）：

```sql
-- ① 有条赞的帖子补齐 / 覆盖（窄表只对有赞的帖子建行，与 upsertLikeCount 的口径一致）
INSERT INTO post_like_count (post_id, like_count, create_time, update_time)
SELECT post_id, COUNT(*), NOW(), NOW() FROM post_like GROUP BY post_id
    ON DUPLICATE KEY UPDATE like_count = VALUES(like_count), update_time = NOW();

-- ② 取消赞到 0 的老行归零（只在有差异时写）
UPDATE post_like_count c
  LEFT JOIN (SELECT post_id, COUNT(*) n FROM post_like GROUP BY post_id) t ON t.post_id = c.post_id
   SET c.like_count = COALESCE(t.n, 0), c.update_time = NOW()
 WHERE c.like_count <> COALESCE(t.n, 0);

-- ③ 评论点赞本来就同事务：只断言，期望 0 行
SELECT c.comment_id FROM post_comment c
  LEFT JOIN (SELECT comment_id, COUNT(*) n FROM post_comment_like GROUP BY comment_id) t ON t.comment_id = c.comment_id
 WHERE c.like_count <> COALESCE(t.n, 0) LIMIT 10;
```

- 【B】执行 §3a 的 `cutover-catchup.sql`。
- **验证**：【A】`SELECT SUM(like_count) FROM post_like_count` == `SELECT COUNT(*) FROM post_like`；【B】两条 `state = 0` 计数为 0、`waiting_qty < 0` 为 0。

**第 4 步 · 对账与清桶**【仅 B】

- 按 §3b 逐 inv 比对 `Σ 桶余量` vs `surplus_qty`，差额记进发布记录（**不修数**）；然后 `DEL` 全部 `bs:inventory:*`。
- **验证**：`--scan` 再跑为 0；差额行已落进 `cutover-<日期>.md`（有差额就写上诊断，没差额写 0）。

**第 5 步 · xxl 注册停用**【仅 B】

```sql
SET @group_id = (SELECT id FROM xxl_job_group WHERE app_name = 'marketing-executor' LIMIT 1);
UPDATE xxl_job_info SET trigger_status = 0, update_time = NOW()
 WHERE executor_handler IN ('inventoryMergeBackstop', 'inventoryBucketWarmup') AND job_group = @group_id;
```

- **验证**：`SELECT executor_handler, trigger_status FROM xxl_job_info WHERE executor_handler IN ('inventoryMerge','inventoryMergeBackstop','inventoryBucketWarmup')` → `inventoryMergeBackstop` / `inventoryBucketWarmup` 两行都是 `trigger_status = 0`；`inventoryMerge` 已不存在（seed 里那条幂等 `DELETE` 早清过）。

**第 6 步 · 最小 DDL**【仅 B】

```sql
-- 目的只有一个：新代码不写 state 列时落 1（= 已合并），旧代码显式传 0，回滚后不会重合历史行
ALTER TABLE physical_marketing_quota_detail ALTER COLUMN state SET DEFAULT 1;
ALTER TABLE virtual_inventory_quota_detail  ALTER COLUMN state SET DEFAULT 1;
```

- **验证**：`SHOW CREATE TABLE …` 里两处都是 `DEFAULT 1`；**不删任何列、不删任何表**（这一步的「不做」比「做」重要）。

**第 7 步 · 起新 jar 并验启动**【A 在前，B 在后】

```bash
cd backend/better-school && mvn -o clean package -DskipTests    # bs 批次
cd backend/marketing     && mvn -o clean package -DskipTests    # marketing 批次
cd backend && scripts/run-app.sh bs                             # 或 scripts/run-app.sh marketing
```

- **验证**：两个端口健康检查 200；启动日志无 `Unknown column` / `Table … doesn't exist`；marketing 的 xxl 执行器上线且 handler 列表 = 4 个（`outboxScan` / `pointsExpireScan` / `drawCountBucketExpireScan` / `raffleCacheWarmup`）；观察 ≥2 分钟无「找不到执行器任务」；日志里**没有** `PostCountAggregator` / `bs-inventory-merge` 字样。

**第 8 步 · 抽样验证与观察期**【A+B】

```sql
-- 库存守恒：逐 inv 期望 0 行（不限量行同样成立）
SELECT q.inv_id, q.surplus_qty, q.waiting_qty, q.issued_qty, COALESCE(d.n, 0) AS deduct_sum
  FROM physical_marketing_quota q
  LEFT JOIN (SELECT inv_id, SUM(quantity) n FROM physical_marketing_quota_detail
              WHERE type = 1 GROUP BY inv_id) d ON d.inv_id = q.inv_id
 WHERE q.waiting_qty + q.issued_qty <> COALESCE(d.n, 0);

-- 计数抽样：逐行相等
SELECT c.post_id, c.like_count, (SELECT COUNT(*) FROM post_like l WHERE l.post_id = c.post_id) AS real_cnt
  FROM post_like_count c ORDER BY c.update_time DESC LIMIT 20;
```

- 走一遍真实链路：一次抽奖（`surplus -= 1, waiting += 1` + 一条 `type = 1` 明细）→ 一次兑换确认（`waiting -= 1, issued += 1`）；一个点赞（`post_like` 与 `like_count` 同事务现形）。
- 读一次新引擎的读数（票 10 留的那一步人工验证）：`BUFFER_FULL` 降级次数 = 0、`drainFailed` = 0；若不为 0 记录「flush 次数 / 请求数」比值，作为分片与窗口的第一次观测基线。
- **验证**：两条 SQL 都为 0 行；链路演示三个数都对；观测项抄进发布记录。

### 6. D9 收口 3 步（不可逆，此时不再考虑回滚）

1. **删列与索引**（在线 INSTANT；撑不住就进一次小窗口）：

```sql
ALTER TABLE physical_marketing_quota_detail
  DROP COLUMN bucket_id, DROP COLUMN state,
  DROP KEY idx_key_cell, DROP KEY idx_pending;
ALTER TABLE virtual_inventory_quota_detail
  DROP COLUMN bucket_id, DROP COLUMN state,
  DROP KEY idx_key_cell, DROP KEY idx_pending;
```

2. **删两张桶表**（**只删这两张**）：

```sql
DROP TABLE physical_marketing_quota_bucket;
DROP TABLE virtual_inventory_quota_bucket;
```

> ⚠️ 同库里还有 `points_bucket` / `draw_count_bucket` —— 那是**用户资产**（积分桶 / 次数桶，见 `CONTEXT.md`），与本改动无关。按白名单写表名，不要按 `%_bucket` 匹配。

3. **删 xxl 注册**（票 08 §5 那一条，此时由「停用」升级为「删除」）+ 更新 `schema.sql` / 文档。

- **验证**：`SHOW CREATE TABLE` 里已无 `bucket_id` / `state`；`SELECT table_name FROM information_schema.tables WHERE table_schema='bs_marketing' AND table_name IN ('physical_marketing_quota_bucket','virtual_inventory_quota_bucket')` 为 0 行；票 08 §5 的验证 SQL ① 为 0 行、② 连跑两遍行数不变。

### 7. 代价与交给下游

**代价（都是真的）**：

- 观察期里 schema 有两列死列、两条死索引、两张死表；`schema.sql` 与线上库形状有 7 天不一致（新装库走新 DDL，存量库等 D9）。
- 多一次在线 DDL 与一次「删注册」的运维动作（但都是低风险窗口外的动作）。
- 回滚清单从 3 条变 4 条（多了「重灌桶」），换掉的是「反向 DDL + 恢复备份」这条根本走不通的路。
- 差额与 `waiting + issued` 断言全靠人工看：本工程没有 metrics 端点、`alarm_email` 为空（票 08 §3），第 8 步是这件事**唯一**的自动化缺席补偿。

**交给下游**：

- **spec**：把 §5 的 8 步、§6 的 3 步原样收进去（它是运维事实，不是代码）；三份脚本落 `marketing/sql/cutover/`（`cutover-catchup.sql` / `cutover-cleanup.sql` / `rollback-warmup.sh`）；把「切换点差额」定为发布记录的必填项。
- **新 ADR（取代 0011 / 0012）**：记「不设开关；切流窗口只做可逆动作，不可逆点推到 D9；回滚 = jar + 重灌桶 + 放开注册；旧兜底在回滚形态里必须回来」。
- **票 10 已留的「切换后抽样对账」** = 第 8 步，不用另立。
- **测试库**：`bs_marketing_test` / `better_school_test` 不必走本清单——落地时直接按新 `schema.sql` 重建（`cat init-test.sql schema.sql`），清单只对存量库有意义。
