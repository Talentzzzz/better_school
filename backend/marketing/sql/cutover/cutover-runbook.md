# 切换运行手册：热点行写入换进程内合并（hot-row-merge-l2w256）

> 配套脚本（本目录）：`cutover-catchup.sql`（在途收口）/ `cutover-cleanup.sql`（对账与清桶）/
> `rollback-warmup.sh`（回滚重灌桶）/ `d9-finalize.sql`（D9 收口，不可逆）。
> 切换记录用 [cutover-record-template.md](cutover-record-template.md) 复制出 `cutover-<日期>.md`，**初值快照是回滚与「三列守恒」的唯一凭据**。
> 形态来源：`.scratch/hot-row-merge-l2w256/spec.md` §6；判据：同目录 `acceptance.md` D1–D3。

## 0. 上线顺序（写死，不临场改）

- **不设开关，一个 PR 换掉**。旧路径踩在将被删的列 / 表 / 桶 / 注册上；新代码不维护桶，双跑买不到「一次配置回滚」。
- **计数（bs）先切、库存（marketing）后切，单位是进程**：商品浏览与帖子点赞在同一个 jar 里，「只让商品浏览先跑一周」不存在。
- **间隔 = 一个日间高峰 + 一次抽样对账**（校园墙高峰 20:00–23:00），不是一周：

| 日 | 动作 |
| --- | --- |
| **D0** | 切 bs（better-school）：8 步中带【A】的步骤 |
| **D1** | 晚高峰后抽样（第 8 步的 D1–D2 两条 SQL + 链路演示） |
| **D2** | 切 marketing：8 步中带【B】/【仅 B】的步骤 |
| **D9** | 收口（`d9-finalize.sql`，**不可逆**，此后不再考虑回滚） |

窗口内 **10–20 分钟停机**，两个人：**执行** + **复核**（每步验证项由复核确认后再走下一步）。
窗口内**只做可逆动作**——所以回滚不需要反向 DDL、不需要恢复备份。

---

## 1. 上线 8 步

标【A】= bs 批次（D0）；【B】= marketing 批次（D2）；无标注两批都做。

### 第 1 步 · 备份与初值快照【A+B，不停机】

```bash
docker exec bs-mysql mysqldump -uroot -pbs123456 --single-transaction --routines better_school > backup-bs-$(date +%F-%H%M).sql
docker exec bs-mysql mysqldump -uroot -pbs123456 --single-transaction --routines bs_marketing    > backup-mkt-$(date +%F-%H%M).sql
```

初值快照 SQL（结果**原样粘进切换记录的「初值快照」段**，模板里有固定粘贴位）：

```sql
-- bs_marketing：台账三列逐 inv（守恒等式的初值）
SELECT inv_id, surplus_qty, waiting_qty, issued_qty FROM physical_marketing_quota ORDER BY inv_id;
SELECT inv_id, surplus_qty, issued_qty             FROM virtual_inventory_quota  ORDER BY inv_id;
-- better_school：点赞合计（旧链路滞后，此时可以不等；切换后必须相等）
SELECT (SELECT SUM(like_count) FROM post_like_count) AS 窄表合计, (SELECT COUNT(*) FROM post_like) AS 关系行数;
```

**验证**：两个 dump 结尾都有 `Dump completed`；三个结果集已进 `cutover-<日期>.md`。

### 第 2 步 · 停流与优雅停机【A+B】

- 有维护页就置维护页；向目标进程发 SIGTERM（`kill <pid>` / 控制台 Ctrl+C）。**不要 `taskkill /F`**——
  它绕过 shutdown hook，合并器的「停收 → 排空 → 关池」（`@PreDestroy close()`）都挂在 context close 上。
- **验证**：退出码 0；日志有 `@PreDestroy` 收尾（排空记录、`drainFailed` 计数）；端口不再监听
  （bs 8080 / marketing 8081）；`SHOW PROCESSLIST` 只剩运维连接。

### 第 3 步 · 在途收口【A：重算点赞；B：catchup 脚本】

**【A】重算帖子点赞**（关系表是真相，旧链路必然滞后）：

```sql
USE better_school;   -- 计数表在单体库，别在 bs_marketing 连接里跑这段

-- ① 有条赞的帖子补齐 / 覆盖（窄表只对有赞的帖子建行）
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

-- ④ 计数 topic 的 outbox 存量直接删行（消费者即将消失；点赞已被重算覆盖，浏览允许丢）
DELETE FROM outbox_message WHERE destination IN ('bs-post-count', 'bs-product-count');
```

**【B】执行在途收口脚本**（含 `type = 2`，不合完则切换后 `waiting` 永久高挂）：

```bash
docker exec -i bs-mysql mysql -uroot -pbs123456 bs_marketing < backend/marketing/sql/cutover/cutover-catchup.sql
```

- **验证**：【A】`SELECT SUM(like_count) FROM post_like_count` == `SELECT COUNT(*) FROM post_like`；
  【B】脚本尾部三条读数（`physical_pending` / `virtual_pending` / `waiting_negative`）**全部为 0**。
  若 ② 的断言非 0（waiting 不足以承接确认），脚本会自己抛错终止（引用一张不存在的表，**不要 --force**）：
  不变量破坏，交人工，不要继续。

### 第 4 步 · 对账与清桶【仅 B】

```bash
docker exec -i bs-mysql mysql -uroot -pbs123456 < backend/marketing/sql/cutover/cutover-cleanup.sql
```

- 逐 inv 执行脚本 ③ 生成的比对命令，`diff` 填进切换记录的「差额表」（**只记不修**；没差额写 0）。
- 然后执行脚本 ④ 注释里的清桶与 `--scan | wc -l` 验证（期望 0）。
- **验证**：`--scan` 再跑为 0；差额行已进切换记录；此后新 jar 运行期 `bs:inventory:*` 不应再出现。

### 第 5 步 · xxl 注册停用【仅 B】

```sql
SET @group_id = (SELECT id FROM xxl_job_group WHERE app_name = 'marketing-executor' LIMIT 1);
UPDATE xxl_job.xxl_job_info SET trigger_status = 0, update_time = NOW()
 WHERE executor_handler IN ('inventoryMergeBackstop', 'inventoryBucketWarmup') AND job_group = @group_id;
```

- **验证**：两个 handler 都是 `trigger_status = 0`；`inventoryMerge` 已不存在（seed 早清过）。
  **只停用、不删**——回滚形态里这两行要回来（D9 才删）。

### 第 6 步 · 最小 DDL【仅 B】

```bash
docker exec -i bs-mysql mysql -uroot -pbs123456 bs_marketing < backend/marketing/sql/cutover/window-ddl.sql
```

- 内容只有两句：`ALTER TABLE <两张明细表> ALTER COLUMN state SET DEFAULT 1`——让新代码不写 `state` 时落 1
  （= 已合并），回滚后旧代码显式写 0 也不会重合历史行。
- **验证**：`SHOW CREATE TABLE` 两处都是 `DEFAULT '1'`；**没删任何列、没删任何表**（这一步的「不做」比「做」重要）。

### 第 7 步 · 起新 jar 并验启动【A 在前，B 在后】

```bash
cd backend/better-school && mvn -o clean package -DskipTests    # A 批次
cd backend/marketing     && mvn -o clean package -DskipTests    # B 批次
backend/scripts/run-app.sh bs        # 或 marketing
```

**启动验证清单**（逐项打勾，进切换记录）：

- [ ] 健康检查 200（bs 8080 / marketing 8081）；
- [ ] 启动日志无 `Unknown column` / `Table ... doesn't exist`；
- [ ] 【B】xxl 执行器上线，handler 恰 **4 个**：`outboxScan` / `pointsExpireScan` / `drawCountBucketExpireScan` / `raffleCacheWarmup`；
- [ ] 【B】观察 ≥ 2 分钟，无「找不到执行器任务」刷屏；
- [ ] 日志里**没有** `PostCountAggregator` / `bs-inventory-merge` 字样。

### 第 8 步 · 抽样验证与观察期（= acceptance.md D1–D3）

```sql
-- D1 库存守恒：期望 0 行
SELECT q.inv_id, q.surplus_qty, q.waiting_qty, q.issued_qty, COALESCE(d.n, 0) AS deduct_sum
  FROM physical_marketing_quota q
  LEFT JOIN (SELECT inv_id, SUM(quantity) n FROM physical_marketing_quota_detail
              WHERE type = 1 GROUP BY inv_id) d ON d.inv_id = q.inv_id
 WHERE q.waiting_qty + q.issued_qty <> COALESCE(d.n, 0);

-- D2 计数抽样：like_count 与 COUNT(*) 逐行相等
SELECT c.post_id, c.like_count, (SELECT COUNT(*) FROM post_like l WHERE l.post_id = c.post_id) AS real_cnt
  FROM post_like_count c ORDER BY c.update_time DESC LIMIT 20;
```

- 链路演示：一次抽奖（`surplus -= 1, waiting += 1` + 一条 `type = 1` 明细）→ 一次兑换确认
  （返回即 `waiting -= 1, issued += 1`）；一个点赞（返回即 `post_like` 与 `like_count` 一致）。
- D3 读数抄进切换记录：`BUFFER_FULL` 降级 = 0、`drainFailed` = 0；记录 `flush 次数 / 请求数`（第一次观测基线）。
- **验证**：两条 SQL 期望 0 行 / 逐行相等；读数已进切换记录。

---

## 2. 回滚（窗口内只做过可逆动作，所以不需要反向 DDL）

**回滚触发条件**（提前定，不临场拍脑袋）：

- 抽奖接口 p99 超基线 3 倍，或 `TIMEOUT_UNKNOWN` 占比 > 1%（合并器在拖住 HTTP 线程）；
- 死信出现 `INVENTORY_PARAM_ERROR`（不变量破坏）；
- 第 8 步抽样出现任一 `waiting + issued <> SUM(type = 1)` 的行。

不会因「启动失败」回滚不了——第 7 步清单当场就能发现；「上线 2 小时后发现超卖」在新模型里不存在
（守卫与扣减同一事务）。

**回滚四步**（顺序执行）：

1. **停新 jar**：优雅停机（SIGTERM），缓冲排空由 `@PreDestroy close()` 承担；
2. **回滚 jar**：两个工程各回上一版；
3. **重灌桶**：`backend/marketing/sql/cutover/rollback-warmup.sh`（按台账 `surplus` 均 `SET` 回 Redis，逐 inv 回读校验）；
4. **放开 xxl 注册**（两行一起执行——`@group_id` 在新会话里要先现取）：
   `SET @group_id = (SELECT id FROM xxl_job_group WHERE app_name = 'marketing-executor' LIMIT 1);`
   `UPDATE xxl_job.xxl_job_info SET trigger_status = 1 WHERE executor_handler IN ('inventoryMergeBackstop','inventoryBucketWarmup') AND job_group = @group_id;`

**回滚形态 ≠ 新形态，两件事必须写清**：

- **`inventoryMergeBackstop` 必须回来**：回滚形态里合并消息仍可能整条丢（消息丢 ⇒ `surplus` 不降 ⇒ 超卖），
  这正是它当初存在的理由。新模型里它退休，回滚形态里它不能缺席；
- 回滚后旧代码显式写 `state = 0`，新合明细落默认 1——两态并存**不会重合历史行**（第 6 步的 DDL 就是为此）。

**回滚治不了的三件事**（写在第一行，避免有人以为回滚等于撤销）：

1. 已暴露的账不平：`waiting` 不足抛进死信的确认，回滚后同样确认不了，仍需人工；
2. 切换窗口丢掉的浏览量（无真相源，永远补不回来）；
3. 延迟剖面换回「Redis 预扣一刀」，容量假设要重新看。

---

## 3. D9 收口（**不可逆**，此时不再考虑回滚）

```bash
docker exec -i bs-mysql mysql -uroot -pbs123456 < backend/marketing/sql/cutover/d9-finalize.sql
```

三步齐：① 删列与索引（在线 INSTANT）→ ② 删两张桶表（**白名单**：同库的 `points_bucket` /
`draw_count_bucket` 是用户资产，绝不按 `%_bucket` 匹配）→ ③ 删 xxl 注册。
脚本尾部自带全部验证 SQL（删列 / 删索引 / 桶表 0 行且白名单外两张健在 / 注册 0 行）。
收尾：`schema.sql` 已随本仓库写成最终形态，存量库自此与新装库对齐。

---

## 4. 孤儿预扣（人工 SQL，不建 job、不建巡检）

预扣提交、`draw_record` 没落库（`RaffleDrawServiceImpl` 不是一个大事务）⇒ 用户没成功但库存已扣。
方向固定：**库存被白扣**。能自愈的部分靠同 token 重试撞 `uk_biz_type` 回放；用户放弃的部分永久挂账，
只有人工 SQL 能找到它——**旧兜底也管不了它**（兜底只按明细搬账）。

```sql
-- 实物段：有预扣明细、但没有对应 draw_record 的行（type = 1，biz_id = record_id）
SELECT d.inv_id, d.biz_id, d.quantity, d.create_time
  FROM physical_marketing_quota_detail d
  LEFT JOIN bs_marketing.draw_record r ON r.record_id = d.biz_id
 WHERE d.type = 1 AND r.record_id IS NULL
 ORDER BY d.create_time;

-- 虚拟段同构
SELECT d.inv_id, d.biz_id, d.quantity, d.create_time
  FROM virtual_inventory_quota_detail d
  LEFT JOIN bs_marketing.draw_record r ON r.record_id = d.biz_id
 WHERE r.record_id IS NULL
 ORDER BY d.create_time;
```

（`draw_record` 与两张明细表同在 `bs_marketing` 库；从单体库查时才需要库名前缀。）
处置按行人工判断：用户重抽成功的会撞唯一键自动回放；确认放弃的记进运维台账，量级进 ADR-0013 的后续观测。
