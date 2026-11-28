# 01: 钱包域平台代收词汇（保留用户 ID 常量 + 双边流水类型）

**What to build:** 把「平台就是 `virtual_wallet` 的一行」这件事落成两个可被下游编译引用的符号，本票只动类型层，不碰任何 SQL、仓储与用例：

1. `bs-types/src/main/java/com/betterschool/types/common/Constants.java`：紧邻现有 `SYSTEM_USER_ID` 新增保留常量 `PLATFORM_USER_ID = "00000000-0000-0000-0000-000000000002"`，Javadoc 写明「平台代收账户：`virtual_wallet` 的一行（余额 + 流水 + 幂等键与普通用户同构），不是真实用户、不出现在任何用户可见接口；与 `SYSTEM_USER_ID`（通知助手）刻意分开，避免一个账号两种角色」。
2. `bs-domain/src/main/java/com/betterschool/domain/wallet/model/valobj/WalletDetailTypeEnum.java`：商品链路从「冻结/解冻」翻到双边语义——
   - 新增 `PRODUCT_PAY("商品支付出账")`、`PRODUCT_REFUND("商品退款入账")`、`PLATFORM_IN("平台代收入账")`、`PLATFORM_OUT("平台出账")`；
   - `PRODUCT_FROZEN` / `PRODUCT_UNFROZEN` **保留枚举常量但标 `@Deprecated`**，注释写明「仅供读取历史流水行（`WalletDetailTypeEnum.valueOf` 反序列化）；商品链路不再写入；历史行一次性映射见 `docs/adr/0019-platform-escrow-account.md`」。不许在本票删除它们——删了会让未迁移的历史库读流水直接抛 `IllegalArgumentException`；
   - `RECHARGE` / `FROZEN` / `UNFROZEN` / `INCOME` 原样保留（非商品链路的历史资金动作）；
   - `PRODUCT_INCOME("商品售卖收入")` 保留，语义不变（卖家入账）。
3. `bs-domain/.../wallet/model/valobj/WalletPayTaskStateEnum.java` 的类注释：把「与支付单 PAID、PRODUCT_FROZEN 流水、余额扣减一起提交」改为「与支付单 PAID、`PRODUCT_PAY` + `PLATFORM_IN` 双边流水（买家出账 + 平台入账）同事务提交」。
4. 检索全仓 `WalletDetailTypeEnum` 的枚举常量引用，**只改注释里的类型名**，不改任何代码分支；`PRODUCT_FROZEN`/`PRODUCT_UNFROZEN` 的代码引用（`WalletRepository.pay`、`IdleCase`）保持可编译——它们的语义改造分别在票 02 / 票 03。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

验收清单：

- [ ] `Constants.PLATFORM_USER_ID` 存在，值与 `SYSTEM_USER_ID` 不同，注释说明「平台代收账户 + 与通知助手区分」
- [ ] `WalletDetailTypeEnum` 含 `PRODUCT_PAY` / `PRODUCT_REFUND` / `PLATFORM_IN` / `PLATFORM_OUT`；`PRODUCT_FROZEN` / `PRODUCT_UNFROZEN` 仍在（`@Deprecated` + 历史读取说明）
- [ ] 枚举顺序/描述与 spec「流水类型」段一致：结算与退款都落 `PLATFORM_OUT`，方向由订单状态与配对行判定（写进 `PLATFORM_OUT` 的注释，别写进代码分支）
- [ ] 本票**只**改上述三个文件；不碰仓储 / mapper / 用例 / 文档（文档是票 05，仓储是用例票 02）
- [ ] 编译闸门：**不运行 mvn / 任何测试**（spec 交付口径由维护者自行验证）；改动只增符号与改注释，不引入未定义引用

**落地注记（实现取舍）：** 待实现者补。
