# 虚拟钱包模块接口

资金托管的底座：余额 / 冻结金额的查询与流水。全部接口需登录（JWT Bearer）。表结构见 `docs/db/user.md` 的 `virtual_wallet` / `wallet_detail`。

## POST /recharge
充值（需登录）。Body: `amount`（正整数，分）。入账落 `wallet_detail`（type=RECHARGE）并加余额。返回 `data = null`。

## GET /balance
我的钱包余额。返回 `data = {balance, frozenAmount}`（无钱包记录时两字段均为 null）；可用余额 = `balance − frozenAmount`（`frozenAmount` 是订单托管中的冻结部分，不参与可用余额）。

## GET /transactions
我的资金流水（分页，`?page=1&pageSize=10`，pageSize ≤ 50）。按时间倒序，返回 `data = {page, pageSize, total, list}`，每条含 `id / type（RECHARGE｜FREEZE｜UNFREEZE｜PAY｜REFUND…）/ amount（正收入负支出）/ orderId / createdAt`。
