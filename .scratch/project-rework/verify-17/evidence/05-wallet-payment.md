# 项 5 钱包支付 —— 冒烟结论

- **脚本**：`smoke_02_05_trade_wallet.py`（buyer `13700017202` / seller `13700017201`）
- **判定**：**PASS**（45 条断言全绿；连跑两次均 PASS，脚本可重复执行）
- **证据摘要**：`02-05-summary.json`、`02-05-sql-log.txt`、`02-05-rerun-stdout.txt`（第二次连跑 stdout）

## 充值口径（先说清「钱从哪来」）

**没有用直插 DB 的方式充值。** 真实机制就是钱包域自带的 mock 充值接口：

- `POST /api/wallet/recharge`（`WalletController.recharge` → `IWalletAccountService.recharge` → `WalletRepository.recharge`）
  → 先插 `wallet_detail(type='RECHARGE', order_id=NULL)`（`INSERT IGNORE`，RECHARGE 不受 `uk_order_type` 约束），再 `balance = balance + amount`。
- 注册时会 `ensureWallet` 赠送初始余额 1000（`WalletRepository.INITIAL_BALANCE`），本脚本在其之上充值 `119.90`。
- 证据：`05-01-recharge.json`（余额 `1000.00 → 1119.90`，恰等于充值额；`RECHARGE` 流水一行）。

## 支付是「资金托管冻结」，不是直接扣 balance

**这一条必须写明，否则会误判。** 商品支付走 `WalletDetailTypeVO.PRODUCT_FROZEN` 冻结：

- `virtual_wallet.balance` **不减**，`frozen_amount` **加** 成交价（`WalletMapper.updateFreeze` 只动 `frozen_amount`）；
  因此「余额减少」的准确口径是 **可用余额 `balance - frozen_amount`**。
- 确认收货时买家 `PRODUCT_UNFROZEN` 把 `frozen_amount` 退回 0（`GREATEST(0, frozen-amount)`，balance 不变），
  卖家 `PRODUCT_INCOME` 使 `balance` 增加成交价 —— 托管结算闭环。
- 本项既断言了「可用余额减少恰等于成交价」，也同时记录了 `balance`/`frozen_amount` 原始值，不存在口径含糊。

## 断言与证据（成交价 `19.90`）

| # | 断言（全部 PASS） | 证据文件 |
| --- | --- | --- |
| 1 | 充值后余额增加恰等于充值额；`wallet_detail` 落 `RECHARGE` 行且金额正确 | `05-01-recharge.json` |
| 2 | 下单成功：`product_order.state=PENDING_PAYMENT`、`price` 快照 `19.90`；商品转 `LOCKED` | `05-02-create-order.json` |
| 3 | 支付后**可用余额 `balance-frozen` 减少恰等于 `19.90`**（`1239.80 → 1219.90`）；`order.state=PAID`；`wallet_detail` 有且仅有一行 `type=PRODUCT_FROZEN`、`order_id`= 该商品订单、`amount=19.90`；`product_order_event` 落 `PAID`（`PENDING_PAYMENT→PAID`/`BUYER`） | `05-03-pay.json` |
| 4 | **重复支付同一订单幂等**：第二次调用返回成功（`code=200`，非报错，走 `payOrder` 的「已 PAID 直接返回」分支）；可用余额不变（`1219.90 → 1219.90`，未二次扣减）；`PRODUCT_FROZEN` 流水仍 **1** 行（`uk_order_type` 冲突 `INSERT IGNORE` 影响 0 行）；`PAID` 事件仍 1 条；订单仍 `PAID` | `05-04-pay-again-idempotent.json` |
| 5 | 交付确认结算：订单 `COMPLETED`；买家冻结归零（`frozen=0.00`，`balance` 不变）、落 `PRODUCT_UNFROZEN` 恰一行；卖家落 `PRODUCT_INCOME`（`order_id`/`amount` 正确）且**余额增加恰等于成交价**（`1019.90 → 1039.80`）；商品转 `SOLD` 并退出公开列表；事件序 `CREATED→PAID→DELIVERED→COMPLETED` | `05-05-confirm-settlement.json` |
| 6 | 重复确认收货幂等：返回成功、卖家余额不变、`PRODUCT_INCOME` 仍 1 行 | `05-06-confirm-again-idempotent.json` |

## 钱包关键数字（第二次连跑，order_id=2）

| 时点 | balance | frozen_amount | available |
| --- | --- | --- | --- |
| 支付前 | 1239.80 | 0.00 | 1239.80 |
| 支付后 | 1239.80 | **19.90** | **1219.90**（−19.90） |
| 重复支付后 | 1239.80 | 19.90 | 1219.90（不变 → 未二次扣） |
| 确认收货后 | 1239.80 | 0.00 | 1239.80（解冻退回） |
| 卖家确认后 | 1019.90 → 1039.80（+19.90 入账） | 0.00 | — |

## 失败/偏差

**无业务缺陷，本项未出现失败断言。**
唯一相关偏差是脚本侧的取数形状问题（`Harness.ok()` 已剥掉 `data` 外层，辅助函数却按完整响应取值），
**属编排问题（脚本），非业务缺陷**，已在 `smoke_02_05_trade_wallet.py` 内修正
（`_page_list()` 同时兼容 `{page,pageSize,total,list}` 与 `{code,info,data:{...}}` 两种形状）。
