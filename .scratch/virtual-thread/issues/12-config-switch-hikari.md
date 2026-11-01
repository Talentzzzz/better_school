# 12: 配置面——四工程开关 + Dubbo 显式写 + Hikari 显式化

**What to build:** 把 spec §2 的纯配置改造面一次落齐，四工程入口线程模型统一切到虚拟线程，且开关有断言防线。三件事：① 四工程 `spring.threads.virtual.enabled: true`；② 3 个 provider 工程（单体 / marketing-asset / timeout-center）显式写 `dubbo.protocol.threadpool: virtual`，不许吃 3.3.5 的默认连带（spec §2.2）；③ 三个没配 Hikari 的工程（单体 / marketing-activity / marketing-asset）显式化 `maximum-pool-size: 10` + `connection-timeout: 3000`，对齐 timeout-center 的 24 / 3000ms 先例（spec §2.3）。两条硬约束随配置带走：不许配 `server.tomcat.threads.max`（翻开关后静默失效）、不许混用 Tomcat 原生 `useVirtualThreads`。

另记**基线**（供票 16 终验对照）：落地前用启动态 + 一次压测中各记一个平台线程数（口径照 spec §5.1 分路径表，用 `jcmd` 或票 04 `HikariProbe` 同款 JMX 读数），数字写进本票 Answer。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] 四工程翻 `spring.threads.virtual.enabled: true`，配置文件里无 `server.tomcat.threads.max`、无 `useVirtualThreads`
- [x] 3 个 provider 工程配置文件显式出现 `dubbo.protocol.threadpool: virtual`（不依赖默认连带）
- [x] 三个工程 Hikari 显式化（池 10 + `connection-timeout: 3000`），timeout-center 的 24 / 3000ms 不动
- [x] Web seam 断言（spec §5.2 #1）：test 配置注册测试专用 `OncePerRequestFilter` 记录 `isVirtual()`，IT 调任意端点后断言为 VT——防开关被翻回
- [x] 落地前后各记一次平台线程数（启动态 + 压测中），基线数字留票面

## Answer

**落地内容**：四处 `application.yml`（各工程 app 模块 main/resources）——① 四工程 `spring.threads.virtual.enabled: true`，注释随行带两条硬约束（不许 `server.tomcat.threads.max` / 不许混用 `useVirtualThreads`）与「实测收益 ≈ 0，不是性能优化」的诚实声明（spec §2.1）；② 单体 / asset / center 三 provider 显式 `dubbo.protocol.threadpool: virtual`，activity 无 `@DubboService` 不写（spec §6）；③ 单体 / activity / asset 显式 `maximum-pool-size: 10` + `connection-timeout: 3000`，center 的 24 / 3000 原样未动。显式写经启动日志实证：测试栈里 provider 导出 URL 已带 `threadpool=virtual`。

**Web seam 断言**（四工程各两件，测试侧零共享模块故逐字同构）：`VtWebThreadProbe`（test classpath 的 `@Configuration`，注册 `Ordered.HIGHEST_PRECEDENCE` 的 `OncePerRequestFilter`，跑在安全链之前——任何端点含 401/404 都先过，断言不挑业务端点）+ `WebHandlerVirtualThreadIntegrationTest`（各继承既有基类，端点分别取帖子列表 / 活动信息 / points ping / 死信列表）。

**code-review 采纳项（两轴同揪，真问题）**：初版测试类命名为 `*IT`，**不匹配 surefire 默认 includes（`**/*Test.java`），常规构建静默跳过——防线成死代码**（此前只经 `-Dtest=` 强制跑过所以假绿不可见）。已改名 `WebHandlerVirtualThreadIntegrationTest` 并用 asset 工程全量（12 = 11+1）实证默认收编。Spec 轴核 spec §2/§5.2/§6 全项合规（硬约束 grep 仅命中注释、activity 确无 `@DubboService`、center 24/3000 未动、无新增信号量）；Standards 轴确认四份探针重复有据（CLAUDE.md §1 三/四工程互独立无共享模块，Javadoc 已自述）。

**基线与落地后数字（票 16 对照；同机、dev 库、jcmd Thread.print 逐秒采样，口径照 spec §5.1 分路径）**：

| 口径 | 落地前 | 落地后 |
| --- | --- | --- |
| 单体启动态平台线程 | 878（`http-nio-8080-exec` 10） | 821（exec **0**） |
| 单体压测中峰值（c=32 / 30s，照票 04 loadtest） | 1490（exec **32**） | 1386（exec **0**） |
| `ConsumeMessageThread_*` 压测峰值 | 100（5 个有消息消费者 × 20） | 80（4 × 20）——**本票不动消费层，清零归票 14** |
| Dubbo provider / 调度扇出 | 空载 0（看峰值口径） | 同左 |
| activity / asset / center 启动态 | 327 / 293 / 173 | 320 / 275 / 179 |

- rps 166.4 → 157.2，落噪声内——原样印证「实测无收益」（spec 诚实声明）。
- 平台线程**总量**被 RocketMQ client 内部线程主导且波动大（`NettyClientPublicExecutor_` 390→680），属 spec §7 out of scope（库内部 I/O 线程），**对照以分路径读数为准，别拿总量当 KPI**。
- 方法与产物：基线 jar 与落地 jar 同源、唯一差异 = application.yml；压测前后照 measure/README 坑 7 关/还原 xxl 组 3 任务（ids 1,2,3,4,6,14,15,16）；原始线程 dump（`measure/samples-baseline|vton/`）与结果 `r-*-c32.json`、脚本 `measure/baseline3.sh` 留 `.scratch/virtual-thread/measure/` 未入库（可复现，体量大）。

**验证**：改名后全量套件四工程 `mvn -o test` 全绿（352 / 73 / 11 / 31 + 四个 seam 用例，0 失败）。**已知波及 / 范围外**：center 的 seam 端点是死信列表（无鉴权是既有已知缺口，票 09 记过）；asset ping 走登录态发真 Dubbo（测试栈无 provider → 500，seam 断言不看业务码，设计如此）。
