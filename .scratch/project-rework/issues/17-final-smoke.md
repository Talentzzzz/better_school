# 票 17 · 终验 A：三服务全栈可起 + 冒烟走查

Type: task
Status: resolved
Blocked by: 05, 10, 12, 13, 14, 15, 16

## 上下文预算（硬约束）

| 项 | 值 |
| --- | --- |
| 目标 | **200–300k**；硬顶 **300k** |
| 预估改文件 | ~8（编排脚本 / compose / 端口与 topic 清单 / 冒烟脚本 + 少量收尾修复） |
| 必读 | 本票；各票 `## Answer` 的「连带影响」段（用 grep 只摘那一段，不要通读全部票档） |
| 不读 | 各服务的实现代码（除冒烟失败时的定点排查） |
| 必跑 | 三服务全量测试 + 全栈启动 + 冒烟清单 |
| 越界信号 | 冒烟暴露的是**业务缺陷**而非编排问题 → 记进 `## Answer` 并另开票，不在本票顺手改业务 |

## 已核实事实

1. 三个后端工程目录：`backend/better-school`（单体）、`backend/marketing`（票 06/07 合并产物）、`backend/timeout-center`；前端 `front`（Vite）。
2. dev 端口：单体 HTTP 8080 / tri 50051 / actuator 9999；marketing HTTP 8081 / tri 50052 / actuator 9998；timeout HTTP 8083 / tri 50054 / actuator 9996。
3. 常驻测试栈（票 05 交接档 §1 已列）：`bs-mysql`(13306)、`bs-redis`(16379)、`bs-minio`(19000)、`bs-nacos-test`(18848)、`bs-rocketmq-namesrv-test`(19876)、`bs-es`(9200)。⚠️ `backend/scripts/test-env.sh` 在这台机器上跑不了（`bash`/WSL 坏），用 docker 直连替代。
4. 测试栈**无 RocketMQ broker、ES 指向不可达端口**，故「走通 MQ」类冒烟只能在 broker 补齐后做；没有 broker 时按票 14 的接缝断言口径给证。
5. 截图/证据统一落 `.scratch/project-rework/verify-17/`（该目录尚不存在，本票创建）。

## 执行纪律（本票专用）

- **全量测试委托子代理**：每服务一个子代理（≤4 并发），各自只回传「命令 + 通过/失败数 + 失败用例名 + 一句归因」。禁止把测试日志原文倒进主窗口。
- **冒烟脚本化**：每个冒烟项写成脚本 / HTTP 调用序列，主窗口只读结论。截图统一落 `verify-17/`。
- ⚠️ 集成测试共用测试库：**同一服务的测试不要并发跑**（票 05 教训：并发 `mvn test` 会互相污染，症状是「手机号已被注册」「Table definition has changed」）。

## Question

判定 Destination 可达：三服务全栈可起、端到端链路走通。

- **编排收口**：端口、Nacos、xxl-job、RocketMQ topic/group 清理——含已废弃的 `referral` / agent / 旧 `lottery_*` topic 与消费组。
- **冒烟走查清单**（逐项留证）：
  1. 发帖 / 评论先审后发（敏感词初筛 → 人工）
  2. 商品上下架
  3. 跑腿单人单全流程（含超时回调）
  4. 1v1 私信
  5. 钱包支付
  6. 积分桶入账 + 消费扣桶
  7. 抽奖全链路（次数 → 责任链 → 库存预扣 → 合并任务 → 发货）
  8. 后台审核
  9. 通知（中奖 / 积分变动）落库
- 拉起顺序与依赖（MySQL / Redis / MinIO / Nacos / RocketMQ / ES + 三服务）写进脚本注释。

## 施工边界（不做什么）

- 不做文档与词条收口（→ 票 18）。
- 不在本票顺手修业务缺陷：发现即记录 + 另开票（除纯编排问题：端口、配置、topic/group 名）。
- 不新开性能压测（复用 `virtual-thread/measure` 既有成果，`map.md` Out of scope）。

## 验收

1. 三服务全量测试各自绿：`backend/better-school`、`backend/marketing`、`backend/timeout-center`（子代理回传摘要 + 测试数）。
2. `front` 的 `npm run build` 绿。
3. 全栈一键起：脚本/compose 可重复执行，三服务健康检查通过（写明端口与健康端点）。
4. 冒烟 9 项逐项留证（截图 / 响应片段落 `.scratch/project-rework/verify-17/`），失败项必须给出「是编排问题还是业务缺陷」的判定。
5. 废弃 topic / 消费组 / xxl 任务清单核对表：列出「仍在注册但已无生产/消费方」的项并清零。
6. `## Answer` 记录：三服务测试数、冒烟逐项结论 + 证据路径、遗留缺陷清单（含另开票编号）。

## Answer

**结论：Destination 可达。** 三服务全量测试 352 用例全绿、`front` 构建绿、全栈一键起幂等可重复、冒烟 9/9 PASS（0 业务缺陷）。全程未发现任何业务缺陷；过程中修掉的 9 处全是脚本/工具面问题（见「冒烟口径修正」）。

### 验收 1 · 三服务全量测试（各一子代理回传摘要）

| 服务 | 命令 | tests | 失败 |
| --- | --- | --- | --- |
| better-school | `cd backend/better-school; mvn -o test` | 230 | 0 |
| marketing | `cd backend/marketing; mvn -o test` | 80 | 0 |
| timeout-center | `cd backend/timeout-center; mvn -o test` | 42 | 0 |

机读记录：`verify-17/evidence/01-tests.md`。⚠️ `target/` 已被后续 `mvn clean package` 清掉，surefire XML 不复存在。

### 验收 2 · front 构建

`cd front; npm run build`（`vue-tsc -b && vite build`）绿 → `verify-17/evidence/02-front-build.txt`。

### 验收 3 · 全栈一键起（主交付物 = `backend/scripts/dev-stack.ps1`）

Windows PowerShell 5.1 专用（这台机器 bash/WSL 坏，`test-env.sh` / `run-app.sh` 跑不了）。动作：`up` / `down` / `status` / `health` / `build` / `db-init` / `db-reset` / `xxl-seed` / `nacos-prune` / `mq-prune`。`up` 七步顺序（中间件 → 三库 → xxl 注册 → Nacos/MQ 清理 → 构建 → 起服务 → 健康检查）与依赖关系写在脚本头注释；健康端点表也在那里——**票面「已核实事实 2」有误**：本仓没有引入 actuator，9999/9998/9996 是 **xxl 执行器端口**（`xxl.job.executor.port`），健康检查走各服务匿名 HTTP 端点。

- 首跑取证：`verify-17/evidence/03-stack-up.txt`（表数 29/22/16、14 个 xxl 任务、三服务健康）。
- **重复执行取证**：`verify-17/evidence/03b-stack-up-rerun.txt`（`up -SkipBuild` 二跑：7 步全过、三服务「已在监听→跳过」、无 `-ResetDb` 不 DROP 库、健康全 ok）。
- 脚本已把 `up` 的退出码与健康结果挂钩（不健康 exit 1），`down` 对 pid 复用加进程名校验防误杀。

### 验收 4 · 冒烟 9 项 —— 9/9 PASS（0 FAIL）

脚本与证据全在 `verify-17/`（`README.md` 是口径与运行说明；跑法 `python .scratch\project-rework\verify-17\smoke_XX_....py`，Python 3.13 标准库 + docker CLI）：

| 项 | 脚本 | 断言 | 报告 |
| --- | --- | --- | --- |
| 1 发帖/评论先审后发 | `smoke_01_08_review.py` | 17 | `evidence/01-review-first.md` |
| 2 商品上下架 | `smoke_02_05_trade_wallet.py` | 45（含项 5） | `evidence/02-product-on-off-shelf.md` |
| 3 跑腿单人单全流程（含超时回调） | `smoke_03_04_errand_chat.py` | 33 | `evidence/03-errand-full-chain.md` |
| 4 1v1 私信 | 同上 | 24 | `evidence/04-direct-message.md` |
| 5 钱包支付 | `smoke_02_05_trade_wallet.py` | 同项 2 | `evidence/05-wallet-payment.md` |
| 6 积分桶 | `smoke_06_07_09_points_lottery.py` | 7 | `evidence/06-points-bucket.md` |
| 7 抽奖全链路 | 同上 | 19 | `evidence/07-lottery-full-chain.md` |
| 8 后台审核 | `smoke_01_08_review.py` | 45 | `evidence/08-admin-review.md` |
| 9 通知落库 | 同上 | 7 | `evidence/09-notification-persist.md` |

运行 stdout 全文：`evidence/01-08-run.txt` / `evidence/06-07-09-run.txt` + 各 `*-summary.json`。无失败项，「编排问题 vs 业务缺陷」判定条款未触发。

**冒烟口径修正（全是脚本侧，共 9 处）**：`smoke_lib.promote_admin` 用错列名（`mobile`→实际 `phone`）；`smoke_01` 里参数名 `comment_ids` 遮蔽同名函数；抽奖脚本 5 处断言口径错（`drawCount` 是兑后余额非增量、FIFO 比较混 int/str 键、`draw_count_bucket` 未按兑换单号过滤、虚拟档「落库即推进」应接受两种态、实物守恒式应为 `surplus+waiting+issued==总量`）等。

### 验收 5 · 注册面清零核对表

`verify-17/evidence/05-registry-checklist.md`（原始清单：`verify-17/broker-topics-{before,after}.txt` / `broker-consumer-groups-after.txt`）：

- **xxl**：删 7 个已无 `@XxlJob` handler 的历史任务行 + 2 个废弃执行器组；现存 14 任务与代码逐条相等。
- **RocketMQ**：删 **6** topic + 6 消费组 + 各自 `%RETRY%`（4 个既有 + **code-review 补录 2 个孤儿**：`bs-errand-timeout` / `bs-errand-auto-complete`——代码零引用但 broker 有残留，已进 `$StaleMqNames` 并重跑 `mq-prune` 验证；`Constants.java` 对应两个死常量一并删除，`mvn compile` 过）。补录后 broker 实存 13 业务 topic + 15 消费组，与代码侧 16 个在用消费组逐条对上（差集 = `bs-notification-broadcast-group` 尚无消费）。
- **referral / 旧 `lottery_*`**：`broker-topics-before.txt` 证明从未注册过（topic 首次生产才建，域删前未达生产），清零口径天然满足，核对表已补判定行。
- **Nacos**：删 10 条已删工程遗留 dataId（dev 2 / test 8）；`better-school-shared.yml` 为三服务启动依赖，保留。

### code-review（双轴，基线 HEAD=ab80f77）与当场修复

Standards 轴 2 硬违规已修：`backend/CLAUDE.md` §10 环境入口改为 `dev-stack.ps1`（`test-env.sh` 降为 bash 环境参考口径，注明本机 bash 坏）、隔离口径表补上 `bs_marketing` / `bs_timeout` 及三个 `*_test` 库。脚本面修复：`.EXAMPLE` 误写 `pwsh`（本机只有 5.1）、`Invoke-Down` pid 复用防误杀、`up` 退出码挂钩健康、xxl 清单 SQL 里 `→` 改 ASCII（mysql 回显转码坑）。Spec 轴 3 项缺漏：Answer 回写（本节）、03b 重复执行取证（已补）、referral 判定行与 §3 计数矛盾（已改）。评审还发现冒烟脚本存在成规模的复制粘贴（4 个脚本各自重复 `promote_admin` / `check` / 栈就绪轮询）——一次性验证产物，判为不重构。

### 遗留清单（均非业务缺陷）

1. `backend/docs/api/README.md:5` 标题仍写「营销服务（8081/8082）」→ **票 18** 文档收口。
2. MySQL 卷躺着已删工程的库（`bs_asset` / `bs_activity` 及各自 `_test`、十几个 `bs_it_*`）→ 未 DROP（破坏性操作，不在本票），**归票 18 一并决定**。
3. 开发 broker 上的 `BenchmarkTest` 空 topic（RocketMQ 压测遗留，非本仓业务）→ 未清，留 `broker-topics-after.txt` 备查。
4. 仓库根 `rafflex-backend/` 仍未跟踪（参考项目）→ **票 18** 的「遗留物入库或删除」条款处理。
5. 冒烟脚本间重复代码（如上）→ 一次性产物，随 `.scratch/` 处置策略归票 18。

### 连带影响清单

- 新增 `backend/scripts/dev-stack.ps1`（此后全栈入口）；`backend/docker-compose.yml` 与 `backend/scripts/run-app.sh` 仅头注释更新。
- `backend/CLAUDE.md` §10 环境口径改写（跑测试前先 `dev-stack.ps1 up`；重建 `*_test` 库暂无 PowerShell 等价命令，需手工 DROP + 重灌 schema.sql）。
- `better-school/bs-types` `Constants.java` 删 2 个零引用常量（无行为变化，better-school 编译通过）。
- 无 DDL / schema 变化；测试栈与开发栈拓扑未动。

<!-- 收口补记：Context used: ~340k（超 300k 硬顶，主因：交接续窗重读环境 + code-review 双子代理 + broker 对账往返 4 次）；未触发拆分，单票完成；再次佐证「验证+收口」型票按 200k 出头立项更稳 -->
