Type: task
Status: resolved

# 02 商品订单资金链路两段化（ProductPayCompositeTxPort 退役 + 商品孤儿扫描）

## 问题

ADR-0016 废止组合事务端口。`ProductPayCompositeTxPort` 两个方法（payOrder / cancelWithRefund）在
`CompositeTxExecutor` 一个物理事务里编排 trade + wallet 两个域的仓储，违反「事务边界 = 仓储」纪律
（fund-consistency 票 06/07 的来路）。本票清退后 `CompositeTxExecutor` 全仓无使用方，组件随之退役。

## 交付

- 删除 `IProductPayCompositeTxPort`（trade 域声明）/ `ProductPayCompositeTxPort`（infra 实现）/
  `CompositeTxExecutor`（含其测试 `CompositeTxExecutorTest`）。
- 商品支付两段化（对齐票 01 的跑腿支付形状）：
  - **资金段先行**：case 层 `walletAccountService.freeze(buyerId, amount, orderId, PRODUCT_FROZEN)`
    （流水 `uk_order_type` 幂等 + 余额条件扣减，余额不足直接失败、订单留待支付）；
  - **翻转段**：trade 仓储自己的「待支付 → 已支付」条件翻转（`payOrderById`，守卫买家 + 前态）。
  - 翻转失败时货款悬置冻结（长款中间态），由孤儿扫描解冻收敛。
- 商品取消两段化（对齐跑腿取消：翻转段先行）：
  - **翻转段**：`cancelOrderWithSearchSync`（含台账回补 + 搜索同步 outbox，单仓储事务，未命中抛异常零残留）；
  - **资金段**：case 层 `unfreeze(buyerId, amount, orderId, PRODUCT_UNFROZEN)`（`uk_order_type` 幂等）。
- 商品孤儿扫描：**复用票 01 的窗口重扫形状**，两个新判据（`CompositeTxExecutor` 判据里的
  「`PRODUCT_FROZEN` / `PRODUCT_UNFROZEN`」与跑腿 FROZEN 同表不同 type）：
  - 钱包侧：`listOrphanFrozen` 扩展 type 过滤或参数化（跑腿 FROZEN / 商品 PRODUCT_FROZEN 两条扫描线，
    各自订单域裁决——商品的订单状态机在 trade 域，`payWithPaidMessage` / `cancelWithSearchSync` 已存在）；
  - 订单侧：trade 域近窗口终态（已支付已取消）按需补解冻；确认收货路径**不改**（幂等回放口径已收敛，
    ADR-0015 明确保留）。
- case 层落点：商品支付的 case 编排位置与票 01 同形（`ProductOrderCase` 顺序调用），
  具体以现有 `ProductPayCompositeTxPort` 调用点（`ProductOrderCase` / `ProductOrderServiceImpl`）为准。
- 文档：`docs/api/trade.md` 两处「经组合事务端口并入一个物理事务」改两段式宣称；
  ADR-0016 Migration 票 B 标完成；CLAUDE.md 的「CompositeTxExecutor 待票 B/C 清退」改为已清退。

## 约束

- 与跑腿同判据：长款中间态允许，短款不允许。
- 集成测试（fund-consistency 票 06/07 交付的 3 条外显断言）只同步注释不跑（无 docker）；
  外显行为预期不变：余额不足阻止翻转、幂等重放无害、翻转未命中零残留。

## 验收

- 全仓 grep `CompositeTxExecutor` / `IProductPayCompositeTxPort` 零命中。
- bs-domain/infrastructure/case/trigger compile + bs-app test-compile 全绿。
- `trade.md` / ADR-0016 / CLAUDE.md 口径同步。
