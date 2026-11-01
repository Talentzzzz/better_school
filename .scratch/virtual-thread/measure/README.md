# 票 04 实测产物：翻 `spring.threads.virtual.enabled` 的 pinning 与吞吐

结论见 [票面 `## Answer`](../issues/04-task-measure-framework-flip.md)。本目录是可复现的脚本 + 原始结果。

## 脚本

| 文件 | 作用 |
| --- | --- |
| `prep.py` | 造数据：注册管理员/作者/读者、发并审核通过 N 个帖子与商品、充值钱包 → `seed.json` |
| `loadtest.py` | 压测客户端（aiohttp 闭循环，256 并发档可用）。10 个 op 的加权混合负载，覆盖帖子列表/详情/评论（Redis+MySQL）、发帖/评论（MySQL+outbox）、图片上传（MinIO）、二手商品读写、跑腿下单+支付（MySQL+钱包+outbox）。`--only-op X` 可收窄到单条路径做诊断 |
| `run_arm.sh` | 跑「一个配置臂」：起新 JVM → 丢弃的预热轮 → c=32/128/256 三档 → 每档并行采连接池与线程账 |
| `HikariProbe.java` | 通过 attach + JMX 读 `HikariPoolMXBean`（active/idle/total/**ThreadsAwaitingConnection**）与 `Threading`/`OperatingSystem`，200ms 一采，落 CSV。需目标 JVM 带 `--spring.datasource.hikari.register-mbeans=true` |
| `abba.sh` | VT off/on 交替（ABAB）跑，用来抵消「DB 越跑越大」的时间混淆 |
| `poolscale.sh` | 池开到 256 的对照点 |
| `vt-pin-only.jfc` | 只开 `jdk.VirtualThreadPinned` 且 **threshold=0ms** 的 JFR 配置（默认 20ms 会漏掉短 pin） |
| `parse_pins.py` | 把 `jfr print --events jdk.VirtualThreadPinned` 的文本按「第一个非 JDK 内部帧」聚合 |
| `summarize.py` / `report_one.py` | 汇总 `r-*.json` |

## 跑法

```bash
cd backend && bash scripts/test-env.sh up          # 容器不自启，先起栈
cd .. && mvn -o clean package -DskipTests -pl bs-app -am   # 见 backend/better-school
# 起应用（register-mbeans 供探针读池）
cd backend/better-school && java -jar bs-app/target/bs-app-1.0.0-SNAPSHOT.jar \
  --spring.datasource.hikari.register-mbeans=true > logs/app-vt.log 2>&1 &
cd .scratch/virtual-thread/measure
python prep.py --log <上面那个日志> --out seed.json
bash run_arm.sh A-vtoff-p10                                    # 基线
bash run_arm.sh B-vton-p10 --spring.threads.virtual.enabled=true
bash run_arm.sh C-vtoff-p64 --spring.datasource.hikari.maximum-pool-size=64
```

## 原始结果

| 产物 | 内容 |
| --- | --- |
| `r-<臂>-c<n>.json` | 每档的 rps / p50-p99 / 错误码分布 / 逐 op 明细 |
| `r-AB-*.json`、`r-PS-*.json` | 交替对照与池 256 对照点 |
| `probe-<臂>-c<n>.csv` | 逐 200ms 的池占用、**等连接线程数**、JVM 平台线程数、进程 CPU |
| `jfr-vton-c256.jfr` | 实验组 pinning 记录（threshold=0ms） |
| `pinned-summary.txt` | 上述 JFR 的聚合结果（`parse_pins.py` 的输出） |
| `results-table.txt` | 汇总表 |

`pinned-raw.txt`（`jfr print` 的文本导出，112MB）**未入库**——体量太大且可随时重生成。`parse_pins.py` 依赖它，重跑前先：

```bash
jfr print --events jdk.VirtualThreadPinned --stack-depth 24 jfr-vton-c256.jfr > pinned-raw.txt
```

## 结论里被丢弃的臂

`abba.sh` 的 `AB-a`（VT off）**数据不可用**：`r-AB-a-mixed.json` 错误率 34.8%、`r-AB-a-upload.json` 错误率 100%（全是压测客户端侧 `ClientConnectorError`），`probe-AB-a.csv` 只采到 95 行（预期 ~390 行）——那一次被测应用没能稳定服务。读结果时不要引用这两个文件。票面 Answer 里的 VT-off 上传口径改用事后补测的 `r-AB-a2-upload.json`。根因见下条。

## 踩过的坑（复现时注意）

1. **Git Bash 的 `$!` 是 MSYS pid**，`jcmd`/attach 认不到 → 探针与 JFR 必须用 `netstat` 拿到的 Windows PID。
2. **JDK 21 不再分发 `management-agent.jar`**，attach 后要用 `VirtualMachine.startLocalManagementAgent()`。**副作用**：调用它会让**被测 JVM** 从此挂上一个本地 JMX agent，直到该进程退出才消失（所以压测完记得把应用进程杀掉，别让它带着 agent 继续跑）。
3. **`ObjectName("com.zaxxer.hikari:type=Pool,*")` 匹配不到**（池名带空格与括号），用 `com.zaxxer.hikari:*` 再按 `type` 前缀过滤，否则会把没有运行态属性的 `PoolConfig` 也捞进来。
4. **增量 `mvn package` 可能不重打 jar**，改完代码要 `clean package`，并核对 `stat` 时间。
5. 起应用前务必确认 8080 没被上一轮的进程占着（脚本里 `kill_app` 就是干这个）。
6. `errand` 的 `reward` 最低 10，写 1 会 100% 业务失败（报「跑腿金额至少 10」）。
6b. **启动失败要显式判**：`run_arm.sh` / `jfr_run.sh` / `rerun_upload.sh` 有「等不到 `Started ...` 就报错退出」的分支，`abba.sh` / `poolscale.sh` 的 `one()` **没有**——这正是 `AB-a` 那次采到脏数据没被发现的原因（应用没起来，负载照样跑完并落了 JSON）。**动这几个脚本时优先把这个分支补齐**，并让探针失败可见（现在是 `>/dev/null 2>&1` + `wait || true` 全吞）。
6c. 五个入口脚本（`run_arm.sh` / `abba.sh` / `poolscale.sh` / `jfr_run.sh` / `diag.sh` / `rerun_upload.sh`）头顶那段 `JAVA`/`JAR`/`app_pid()`/`kill_app()` 是**逐字复制的**，且已经漂移（6b 那条）。要长期用就该抽一个 `common.sh`；本轮没抽。
7. 压测前临时关掉单体 xxl-job 的 8 个启用任务（`job_group=3`），跑完还原：
   `UPDATE xxl_job.xxl_job_info SET trigger_status=0 WHERE job_group=3 AND trigger_status=1` → 还原 `id IN (1,2,3,4,6,14,15,16)`。
8. 本轮压测把联调库 `better_school` 灌脏了（帖子 ~1.7 万、评论 ~2.1 万、outbox ~8.6 万、跑腿单 ~1.7 万），读路径因此比初始慢约 9 倍——复现前建议先清理。
