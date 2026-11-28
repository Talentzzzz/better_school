# 05: 文档与 ADR 同步「钱由平台代收」口径

**What to build:** 把「买家先把钱付给平台、平台托管、完成/取消时平台出账」写进领域文档、DB 文档与 ADR，让下一个人不会按「冻结」理解代码。本票**只动文档与 `sql/` 注释**，不碰任何 Java 代码。

**Blocked by:** None (can start immediately；口径已由 spec 定死，方法名与类型名照 spec 写)

**Status:** ready-for-agent

### 1. 新增 ADR `docs/adr/0019-platform-escrow-account.md`

记录本次决策（照仓库既有 ADR 体裁：标题 / 一句话决定 / 为什么改 / Decision / Considered Options / Consequences / Migration / References）：

- 平台账户 = `virtual_wallet` 的一行（`user_id = Constants.PLATFORM_USER_ID`，`balance` 从 0 起，懒创建 `INSERT IGNORE`），不新增账户表、不改 `payment_order` / `product_order` 结构。
- 三笔资金归属表（支付 / 完成 / 取消超时 × 买家 / 平台 / 卖家），以及「三笔必须一起改」的理由（只改支付会让平台只进不出、确认收货后总账凭空多钱）。
- 流水双边语义：`PRODUCT_PAY`（买家支付出账，承接原 `PRODUCT_FROZEN`）、`PRODUCT_REFUND`（买家退款入账，承接原 `PRODUCT_UNFROZEN`）、`PRODUCT_INCOME`（卖家入账）、`PLATFORM_IN` / `PLATFORM_OUT`（平台收/付；结算与退款都走 `PLATFORM_OUT`，方向由订单终态判定，同订单二者互斥故 `uk_order_type` 天然防双出账）。
- 幂等锚 = 业务侧那一行（买家支付行 / 卖家结算行 / 买家退款行），`INSERT IGNORE` 影响 0 行即整笔视为已处理。
- 余额守卫：买家出账 `balance - frozen_amount >= amount`；平台出账 `balance >= amount`，不足记 ERROR + 整笔回滚。
- `frozen_amount` 在商品链路退役（列保留、历史值保留，只作买家出账守卫的减项）。
- 对账不变量：平台 `balance` = Σ（有 `PLATFORM_IN` 无 `PLATFORM_OUT` 的订单金额）；本期只定口径，不建定时对账 job。
- 资金孤儿扫描判据从「有 `PRODUCT_FROZEN` 无 `PRODUCT_UNFROZEN`」换成「平台有入账无出账」，裁决仍「宁可长款」。
- `Considered Options`：新建账户表 / 只改支付一笔 / 给支付单加 REFUNDED 状态 / 保留冻结并另加平台行（双记一次），各自为何否。
- `Migration`：历史 `wallet_detail` 行一次性映射 `PRODUCT_FROZEN → PRODUCT_PAY`、`PRODUCT_UNFROZEN → PRODUCT_REFUND`（见第 4 条 SQL）；开发/测试库可直接重建。
- `References`：ADR-0015、ADR-0016、ADR-0018、`docs/db/user.md`、本 spec。

### 2. ADR-0018 加一行取代注记

在 `docs/adr/0018-wallet-as-payment-gateway.md` 顶部（标题下第一段之前）加一条引用块：「> 资金归属口径已被 `0019-platform-escrow-account.md` 取代：支付不再冻结买家余额，而是买家出账 + 平台入账；支付单、收银台、回调与状态机仍按本文。」并把文中「已支付订单取消仍走原解冻链路（PRODUCT_UNFROZEN）」这类句子就地补一句「（口径见 0019：现为平台出账 + 买家入账）」。

### 3. `CONTEXT.md`

- 「支付」段（`CONTEXT.md` 第 ~185 行起）补两件事：平台代收账户存在；三笔资金归属（支付 = 买家 → 平台，完成 = 平台 → 卖家，取消/超时 = 平台 → 买家）。
- 新增词条 **平台代收账户**：`virtual_wallet` 的一行、保留 `user_id`、余额从 0 起、不出现在任何用户可见接口、懒创建；`_Avoid_`：平台钱包、商户号、平台用户（那是真实账号）。
- 新增词条 **平台出账**：结算与退款共用的资金动作，方向由订单终态（`COMPLETED` / `CANCELLED`）判定；余额不足即整笔回滚 + ERROR 告警；`_Avoid_`：解冻、退款流水（退款是业务动作，平台出账是账务动作）。
- 「关闭支付单」词条里「已支付订单的退款仍走原解冻链路（`PRODUCT_UNFROZEN`）」改成「已支付订单的退款由平台出账退回买家（`PLATFORM_OUT` + `PRODUCT_REFUND`）」。`_Avoid_` 里的「退款走解冻流水」同步改。

### 4. DB 文档与 SQL 注释

- `backend/better-school/docs/db/user.md`：
  - `virtual_wallet` 段：写明平台账户就是本表一行（`user_id = Constants.PLATFORM_USER_ID`、`balance` 初始 0、懒创建），`frozen_amount` 在商品链路退役（列保留、历史值仅作买家出账守卫的减项）；「可用余额 = balance − frozen_amount」保留但注明对商品链路只在支付扣减守卫里生效；补对账不变量一句。
  - `wallet_detail` 段：`type` 枚举行改为 `RECHARGE/FROZEN/UNFROZEN/INCOME/PRODUCT_PAY/PRODUCT_REFUND/PRODUCT_INCOME/PLATFORM_IN/PLATFORM_OUT`（旧 `PRODUCT_FROZEN`/`PRODUCT_UNFROZEN` 标「历史，已映射」）；补「每笔商品资金动作落双边流水」「幂等锚 = 业务侧那一行」「平台行 `user_id` = 平台」「结算与退款共用 `PLATFORM_OUT`，同订单互斥」。
  - `payment_order` 段（第 ~113 行）：支付事务口径改成「支付单 WAIT_PAY→PAID → 买家 `PRODUCT_PAY` + 平台 `PLATFORM_IN` 双边流水 → 买家余额条件扣减 + 平台余额入账 → 回调任务行」，失败整笔回滚。
  - `wallet_pay_task` 段（第 ~133 行）：「行在 ⇒ 支付单已 PAID 且钱已由平台代收」。
- `backend/better-school/docs/db/idle.md`（第 ~136 行）：托管资金三类流水改成新的双边口径（`PRODUCT_PAY`/`PRODUCT_REFUND`/`PRODUCT_INCOME` + 平台 `PLATFORM_IN`/`PLATFORM_OUT`），并写「确认收货 = 平台结算给卖家」。
- `backend/better-school/docs/api/idle.md`（第 ~110 行）：确认收货的资金描述改为「平台把代收货款结算给卖家（平台出账 + 卖家入账），买家余额不变」。
- `backend/better-school/docs/api/payment.md`（第 ~33、~53 行）：支付时的资金动作改为「买家余额扣减 + 平台入账」，取消退款改为「平台出账退回买家」。
- `backend/better-school/docs/db/outbox.md`（第 ~31 行）：把 `PRODUCT_FROZEN` 冻结流水改成 `PRODUCT_PAY` + `PLATFORM_IN` 双边流水。
- `backend/better-school/sql/schema.sql`：`wallet_detail.type` 的列注释换成新类型串；`virtual_wallet.frozen_amount` 注释补「商品链路已退役（仅历史残留，作买家出账守卫的减项）」；`payment_order` / `wallet_pay_task` 上方的注释块同步支付事务口径。**不改任何列、索引与约束**。
- 新增 `backend/better-school/sql/migrations/2026-11-29-wallet-detail-type-mapping.sql`：一次性历史映射（可重复执行、幂等）：

  ```sql
  UPDATE wallet_detail SET type = 'PRODUCT_PAY'    WHERE type = 'PRODUCT_FROZEN';
  UPDATE wallet_detail SET type = 'PRODUCT_REFUND' WHERE type = 'PRODUCT_UNFROZEN';
  ```

  文件头注释写明：本次改造前（冻结口径）落的历史行，执行一次即与新类型对齐；之后可重复执行（无匹配行即无操作）；开发/测试库亦可直接 `scripts/test-env.sh reset-test-db` 重建。

验收清单：

- [ ] 新 ADR 0019 存在且含三笔归属表 + 对账不变量 + 历史映射 + 被否方案；ADR 0018 顶部有取代注记
- [ ] `CONTEXT.md` 支付段与两个新词条口径一致，`关闭支付单` 不再说「退款走解冻」
- [ ] `docs/db/user.md` / `docs/db/idle.md` / `docs/db/outbox.md` / `docs/api/idle.md` / `docs/api/payment.md` 中「冻结/解冻」作为**现行**口径的表述清零（历史注记与 `Avoid` 词可留）
- [ ] `schema.sql` 只改注释；`migrations/` 下新增映射 SQL
- [ ] 全仓 grep `PRODUCT_FROZEN` / `PRODUCT_UNFROZEN`：只剩「历史 / 已映射 / 枚举兼容」语义的表述
- [ ] 不碰任何 Java 代码（票 01–04 负责）；**不运行 mvn / 任何测试**

**落地注记（实现取舍）：** 待实现者补。
