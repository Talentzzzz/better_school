Type: task
Status: resolved

## Comments

- 2026-11-20 完成：`CompositeTxExecutor`（REQUIRED 物理事务编排）+ `CompositeTxAction` 回调接口落 `bs-infrastructure/infrastructure/tx/`，4 条外显行为单测随交付（未执行，维护者验证）。提交 `ec66bad`，合并 `457b715`。02–07 经消费方域 port 接口 + 基础设施实现（用本组件编排）接续。

# 01 基础设施组合事务端口

Blocked by: （无，prefactor）

## 问题

spec 的 Implementation Decisions 要求：资金链路全部用**组合事务端口**消灭中间态——基础设施层新增端口，照营销侧积分扣减端口的形状：一个 `TransactionTemplate`（REQUIRED）内编排多域动作，参与域仓储同为 REQUIRED 自动并入同一物理事务；域 A 不写域 B 的表，跨域动作经端口委托。

## 交付

- 在基础设施层新增组合事务端口（确切落点、命名、形状以 [research.md](../research.md) 中营销积分扣减端口的调研为准；如营销侧已有可复用的现成端口，优先复用并按需泛化，而不是再造一个）。
- 端口语义：外层开 REQUIRED 事务 → 依次编排多域动作（订单状态条件翻转 + 钱包资金动作 + outbox / feed 索引行）→ 任一步抛异常整事务回滚 → 参与方仓储 REQUIRED 自动并入。
- 后续票（02–07）只通过该端口编排跨域动作，不各自手搓 `TransactionTemplate` 编排。
- 附最小单测（外显行为：一步失败整事务回滚、状态不翻转），**不运行测试套件**。

## 验收

- 端口可被跑腿 / 商品用例层注入并编排「订单域动作 + 钱包域动作」。
- 域边界不破：域 Service 不直接写对方表（编译期由依赖方向保证）。
