# 06 infra 层包结构重组

Type: task
Status: done
Blocked by: 05

## Question

执行（合并后的单一 infrastructure 模块内）：

1. infra 层统一为 `adapter/{port, repository}`（实现类按域分子包）+ 平级技术包 `dao / merge / ws / cache / search / mq / outbox / security / sharding`，技术包**按需建**（marketing 侧原本没有 cache/search 就不建），不建空包；
2. `adapter/port`、`adapter/repository` 的实现类想用什么就从上述平级技术包取用；技术包之间以及技术包→adapter 不允许反向依赖；
3. marketing 侧按域 infra 子树（inventory/points/raffle/rebate 下的 `adapter`、`dao`）压平：adapter 实现并入统一 `adapter/{port, repository}` 对应域子包，dao 并入统一 `dao/<域>`；`common/{dao, outbox, security}` 并入对应平级包；
4. **domain 层 `domain/<域>/adapter/{port, repository}` 接口一律不动**。

完成标准：全 reactor `compile` + `test-compile` 绿；独立 commit；不跑测试。

## Answer

（commit `99639b7`，2026-11-23，分支 chat-ws-layering-rollout）

- **搬移清单**（git mv 保历史）：`adapter/cache/post` → `cache/post`（1 类）；`adapter/search` → `search`（1 类）；`adapter/merge`（计数引擎 15 类）→ `merge/count`，库存引擎留 `merge` 根——与 research 口径（counting at `infrastructure.merge.count`）及 application.yml `merge.count.*`/`merge.batch.*` 配置键对齐；主+测 5 个测试文件随迁。
- **违规消解**：`CountFlushDispatcher`（引擎唯一出口，按动作分发到 community/trade/errand 三域仓储）原在 merge 包反向 import 三个具体 repository，违反「技术包→adapter 不允许」——移入 `adapter/repository` 根包（横跨三域、无单一归属域，不放任何领域子包）。`@Primary`/`@Lazy`/构造注入逐字保留；FqcnAnnotationBeanNameGenerator 下 bean 名随 FQCN 变化但注入一律按类型，装配不受影响。
- **引用同步 41 文件**：bs-infrastructure 主代码（3 repository import）、测试 5 件；bs-app 的 CountMergeConfig、12 个集成测试、CountMergeConfigTest、启动类 javadoc 包名示例。
- **终态核验**：adapter 下仅 `port`/`repository` 两棵树；技术包 = `cache/dao/merge/outbox/search/security/sharding/ws`（`mq` 两侧原本都没有，按需不建）；技术包→adapter import 零残留；`git diff -- bs-domain` 零改动（domain 接口未动）；全 reactor `compile + test-compile` 绿（未跑测试，按票面标准）。
- **遗留归票 08**：backend/CLAUDE.md §3 的 infrastructure 树未覆盖两处新形态——`merge/count` 子包与 `adapter/repository` 根级横域组件（CountFlushDispatcher）；文档同步时补一行说明。
