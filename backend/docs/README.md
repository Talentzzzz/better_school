# backend 文档索引

> better-school 后端文档：两个互相独立的 Maven 工程——单体 `backend/better-school/`（bs-* 七模块）+ 营销 `backend/marketing/`（票 06 并入原 `marketing-asset` 的积分域、票 07 并入原 `marketing-activity` 的抽奖域）。超时中心已内嵌进单体，不再是独立工程（见 [`architecture/timeout.md`](architecture/timeout.md)）。

| 目录 | 内容 |
| --- | --- |
| [`db/`](db/README.md) | 营销域库表总览（单体与营销各工程的总览在各工程 `docs/db/README.md`） |
| [`api/`](api/README.md) | 营销域接口总览（同上，各工程 `docs/api/README.md`） |
| [`architecture/marketing-services.md`](architecture/marketing-services.md) | 营销两服务怎么跑起来、怎么改不翻车（端口 / Nacos / Dubbo / 契约变更纪律） |
| [`architecture/timeout.md`](architecture/timeout.md) | 内嵌超时中心怎么跑（同事务注册 / 16 分表 + ShardingSphere hint 强制路由 / xxl 分片广播 + 有界并行 / 进程内回调 / 死信管理面） |
| [`testing.md`](testing.md) | 集成测试设施：怎么跑、约定、Nacos/Dubbo 的特殊处理 |

> 超时中心的上线/回滚清单与一次性存量迁移工具是历史产物，已归档到
> `.scratch/timeout-inline/archive/`（旧工程已删除，不再随代码演进）。

编码规范见 [`../CLAUDE.md`](../CLAUDE.md)（DDD 分包分模块约定），需求以 `.scratch/<effort>/spec.md` 为准。

**构建顺序**：两工程无 reactor，跨工程契约经本地仓库解析。改了契约或首次构建时按序执行——
先在 `better-school/` 下 `mvn install`（`bs-api` 是营销服务消费的单体契约），再构建 `marketing/`。
（票 07 起营销只有一个工程：`marketing-api` 是它自己的模块，不再需要跨工程安装顺序。）

**改库表/接口后必须同步的文档**：各工程的 `docs/db/`、`docs/api/`，另加本目录的
[`architecture/timeout.md`](architecture/timeout.md)（超时表/管理面变更时）。

**起应用入口是 `scripts/dev-stack.ps1`**（票 17 引入的 Windows 全栈一键起：`up`/`down`/`status`/`health`/
`build`/`db-init`/`db-reset`/`xxl-seed`/`nacos-prune`/`mq-prune`；本机 bash/WSL 不可用，bash 环境可参考
`scripts/run-app.sh`，svc 键 `bs|marketing`）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dev-stack.ps1 up
```

`dev-stack.ps1` 从工程目录起 jar，**不挂 JFR**；需要 JFR 持续 recording 时用 `scripts/run-app.sh`（它默认挂）
——虚拟线程常态化观测靠它：`jdk.VirtualThreadPinned`
（`threshold=0ms`，默认 20ms 会漏短 pin）+ 线程统计事件，配置在 `backend/jvm/bs-vt-recording.jfc`，落盘
`<工程目录>/logs/jfr/`（不入库），轮转 `maxsize=200m` / `maxage=2d`。取数：
`jcmd <pid> JFR.dump name=bs-vt filename=...`；注意 `taskkill /F` 硬杀会绕过 shutdown hook，让文件停在 0 字节。
要看池的 `active`/`awaiting` 加 `--spring.datasource.hikari.register-mbeans=true`。裁决与验收口径见
`.scratch/virtual-thread/spec.md` §5。
