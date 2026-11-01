# 票 18 · 终验 B：文档 / 词条收口 + grep 清零 + sql.md 回写 + map 收口

Type: task
Status: resolved
Blocked by: 17

## 上下文预算（硬约束）

| 项 | 值 |
| --- | --- |
| 目标 | **≤200k**；硬顶 **300k** |
| 预估改文件 | ~20（`CONTEXT.md`、`backend/docs/**`、各服务 `docs/{api,db}/*`、`sql.md`、`map.md`、`CLAUDE.md` 若需） |
| 必读 | 本票；各票 `## Answer

**结果：文档 / 词条 / 事实源 / 地图四处全部收口；5 份比对脚本全绿；grep 清零 15 词全零；三服务 `test-compile` 绿。零 DDL、零业务行为变更（触及的 Java 只有注释与测试夹具文案）。**

### 1. 比对脚本全绿（验收 1、3）

| 脚本 | 结果 | 本票改动 |
| --- | --- | --- |
| `cmp_schema.py` | 29/29 表全等（原 2 表 COMMENT 差） | 脚本未动；差异回写 sql.md（见 §3） |
| `cmp_schema_marketing.py` | 22/22 逐表逐列全等 | 无 |
| `cmp_db_docs.py` | 0 问题（原 2 幽灵表） | 修标题口径：`# chat 域表（…）`/`# outbox 域表（…）` 是域总览标题不是表名；表标题识别改为「`# 名（` 或 `# 名`独立成行」 |
| `cmp_api_routes.py` | 0 问题（原 67+4 全是口径误报） | 修三处 + 两处容忍：①控制器注解带附加属性（`@PostMapping(value="/upload", consumes=…)`）原正则漏匹配；②文档正文是相对路径（`### GET /rooms`），模块前缀改按 `docs/api/README.md` 总览表解析，README 未声明前缀的（credit/search）按全路径字面量兜底；③新增 `.../后缀` 连写简写（trade.md 的 `POST .../sold`）与 WS 握手 URL（`/ws/chat`）容忍 |
| `cmp_mapper_params.py` | 无问题 | 无 |

`sql.md` 终版与 `better-school/sql/schema.sql`、`marketing/sql/schema.sql` 逐表逐列全等（上表前两行为证）；timeout-center 无独立比对脚本，`sql.md` §3 由票 15 收口（schema-timeout-task.sql 是其 DDL 事实源），本票未动 §3。

### 2. grep 清零证据（验收 2）

口径：`git grep -i -F <词> -- backend front CONTEXT.md`（排除 logs/target/node_modules/dist；logs 不入库本就零命中），终验输出 15 词全 PASS。清洗清单：

| 词 | 原残留 → 处置 |
| --- | --- |
| `求购`/`want_to_buy`/`WantToBuy` | 5 个 Java javadoc（钱包冻结轮次 ×2、注册 outbox DAO、OutboxSender 撤销段、到期扫描 job）改通用措辞；`timeout-center.md` §8/§9 重写；`dev-stack.ps1` 注释 |
| `agent 审核` | VT 消费者清单测试注释、`dev-stack.ps1` 注释 |
| `群聊`/`多人单` | chat/errand 域文档墓碑改「群形态/组队形态已随票 04 下线」；`CONTEXT.md` 2 处 Avoid；测试注释 |
| `拉新`/`referral` | `dev-stack.ps1` 注释、`front/vite.config.ts` 注释 |
| `成色`/`condition` | 6 个测试夹具描述「成色好」→「九成新」（无断言依赖，已核）；`docs/db/trade.md` 墓碑改「商品状况描述维度已整体下线」 |
| `lottery_`（区分大小写） | 营销 docs 墓碑 8 处去旧表名字面量、`marketing-types Constants` javadoc、`schema.sql` 2 行 `--` 注释（注释行非 DDL，比对脚本剥注释） |
| `gatekeeper`/`errand_evaluation`/`points_lot`/`points_consume` | 基线即零 |

**唯一保留的字面量**：`dev-stack.ps1` 清理数组里的 `referralOutboxScan`、`bs-want-paid` 等——那是 `mq-prune`/`xxl-seed` 按**字面量**删 broker/xxl-admin 残留的功能性清单（票 17 冒烟取证依赖），不是能力残留。`lotteryDeductMerge` 等驼峰 handler 名不含 `lottery_`（无下划线），同列保留。

### 3. sql.md 回写汇总（验收 3）

- §1 `post_like_count.like_count` 列 COMMENT 去「，见下方说明」自指（对齐 schema.sql）；§1 `room.state` COMMENT 补「当前恒 1v1，SEALED 不再产生，列随口径保留」（票 04 后口径）。
- §2 来源行：`schema-points.sql`/「设计稿尚未落库/schema-lottery.sql 11 张」→「`backend/marketing/sql/schema.sql`（票 08 落地 22 张、票 13 删过渡文件，唯一 DDL 事实源）」——票 08 的「表数 21→22」实际早已是 22，本票修正的是**来源描述**过时。
- 票 11 的 `strategy_weight`→`draw_strategy_weight` 规范化票 11 已回写完毕，本票核对无再改项；票 12/13/14 无新增回写项。

### 4. CONTEXT.md 词条全集复核（验收 4）

| 分区（词条数） | 现状 |
| --- | --- |
| 评论区（15） | 保留；「互动通知/评论总数」与 MQ 链路一致（票 05 口径） |
| 私信会话（21） | 保留；「会话（恒 1v1）/消息类型/对家角色」与票 04 后一致；**改写 2 处**：会话 Avoid 去「群聊」、消息类型 Avoid 的「系统消息（已随群聊下线）」→「已下线」（grep 清零口径） |
| 站内通知（2） | 保留（票 05/15 口径一致） |
| 跑腿订单（7） | 保留；「状态流转记录」即订单事件流水词条，与 `*_order_event` 实现一致（票 05） |
| 闲置商品（7） | 保留；「互动计数」即计数窄表 + 窗口聚合词条（票 05） |
| 营销积分（3） | 保留；积分桶/抽奖次数桶/行为返利与票 10 实现一致 |
| 抽奖决策侧（3）/ 抽奖主链路（8） | 保留；责任链/权重档/表预热/预扣/发货闭环/奖品状态推进与票 11/12/13/14 一致 |
| **超时关闭（2）** | **新增**：超时任务 / 注册 outbox（票 10/15 能力此前无词条；票面点名的「超时中心」补齐） |
| 审核与信用（1） | **改写**：信用分词条去「跑腿评价随本轮下线」墓碑（已删能力字样），改「信用分只减不增（当前无加分类型）」 |
| 匿名（已移除）（1） | 保留——「（已移除）」词条是对已删概念的显式防再引入声明，非能力残留（票面清零词不含「匿名」） |

### 5. 服务文档 / backend/docs 收口

- **docs/api 补 4 块缺口**（代码有、文档无，cmp 脚本修口径后现形）：新增 `wallet.md`（3 路由）、`credit.md`（6 路由）、`search.md`（4 路由）三篇 + README 总览三行；`trade.md` 商品订单一句话系列展开为 7 条路由（pay/confirm/cancel 的资金语义照 `ProductOrderCase` 核对后写）；`moderation.md` 补敏感词/审核员管理面 5 路由（仅 ADMIN）。
- **backend/docs**：`api/README.md` 端口 8082 → 8081（票 17 遗留①）；`README.md` 起应用入口改 `dev-stack.ps1`（run-app.sh svc 键实际已收敛 `bs|marketing|center`，一并写明）；`architecture/timeout-center.md` §8/§9 由「三处登记/三 taskType/求购取消边」重写为现势——**两处登记**（Product/Errand createOrder）、**两 taskType**、取消边 `cancelAsync` **当前无生产调用方**（能力保留、机制说明改通用口径）、§9 关单动作两路。
- 营销/超时中心 docs 与代码口径核对：营销 22 表文档全覆盖（7+6+8+outbox），超时中心槽位公式注释漂移 4 处统一（map「Not yet specified」移交项）。

### 6. map 收口（验收 5）

- **Destination 标注达成**（票 17 冒烟 9/9 + 票 18 收口）；施工顺序表 18 标「已收口」。
- **Decisions so far 补齐 08/09/10/12/16/18 六节**（原缺 08/09/10/12/16），至此 06–17 全部结论入图。
- **Not yet specified**：前端留白、跑腿评价影响两项随票 16 关闭（划线留痕）；槽位公式漂移随本票关闭；票 12/13/14 拆片预案项关闭（均未触顶）。无未决项转出。

### 7. 遗留物处置与转出项（验收 6 的「明确留下」清单）

- **入库留用**：`.scratch/project-rework/` 的 5 份 `cmp_*.py` + 3 份 schema tsv 是可重复对账工具，原地保留（改表/改接口后复跑即可）。
- **一次性证据**：`Migrate/Compare-*.ps1`、`verify-14/16/17`、`walkthrough-16/`、`06/07-package-mapping` 原地保留作验收证据，不再演化。
- **`05-handoff.md`** 顶部已加「历史件」标注（原文未改，符合 map 的历史档纪律）。
- **`rafflex-backend/`**（参考项目，含独立 `.git`）：**留在磁盘、不入库、不删除**——嵌套 git 库无法入库、删除属破坏性操作越出票面边界；已加 `.gitignore` 保持 `git status` 干净。若要删请人工执行。
- **转出项（记录不执行，非本票可收）**：① MySQL 开发卷里已删工程旧库（`bs_asset`/`bs_activity` 及 `*_test`、`bs_it_*`）未 DROP，破坏性操作留给使用者（`dev-stack.ps1 db-reset` 只重建三个在用开发库）；② broker `BenchmarkTest` 空 topic 留 `verify-17/broker-topics-after.txt` 备查；③ 跨工程死契约 `bs-api IUserQueryService.getBasicBatch`（票 09 起全网零调用方）——动它要重装 bs-api 并重启，归后续清理票；④ `types/api.ts` `NotificationType` 缺 4 个新枚举值（前端 `Record` 兜底不影响渲染）——同票可顺手。

### 8. 本票触及但不改行为的说明

Java 侧 12 个文件均为**注释或测试夹具文案**（javadoc 措辞、测试断言注释、`Map.entry("description", …)` 样例值），`mvn -o test-compile` 三服务全绿；无 DDL、无生产逻辑变更，未跑全量测试套件（票 17 已验 352 全绿，本票零行为面）。

<!-- Context used: ~170k（未超 200k 目标，未触发拆分）；连带影响清单见 §2/§5/§7 -->
