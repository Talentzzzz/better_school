# 06 infra 层包结构重组

Type: task
Status: open
Blocked by: 05

## Question

执行（合并后的单一 infrastructure 模块内）：

1. infra 层统一为 `adapter/{port, repository}`（实现类按域分子包）+ 平级技术包 `dao / merge / ws / cache / search / mq / outbox / security / sharding`，技术包**按需建**（marketing 侧原本没有 cache/search 就不建），不建空包；
2. `adapter/port`、`adapter/repository` 的实现类想用什么就从上述平级技术包取用；技术包之间以及技术包→adapter 不允许反向依赖；
3. marketing 侧按域 infra 子树（inventory/points/raffle/rebate 下的 `adapter`、`dao`）压平：adapter 实现并入统一 `adapter/{port, repository}` 对应域子包，dao 并入统一 `dao/<域>`；`common/{dao, outbox, security}` 并入对应平级包；
4. **domain 层 `domain/<域>/adapter/{port, repository}` 接口一律不动**。

完成标准：全 reactor `compile` + `test-compile` 绿；独立 commit；不跑测试。

## Answer

（待执行后记录 commit hash 与要点）
