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

**Status:** resolved（`PLATFORM_USER_ID` 与 4 个双边语义流水类型已进类型层，`PRODUCT_FROZEN`/`PRODUCT_UNFROZEN` 降为 `@Deprecated` 历史读取类型、未删除；只动 3 个文件）

验收清单：

- [x] `Constants.PLATFORM_USER_ID` 存在，值与 `SYSTEM_USER_ID` 不同，注释说明「平台代收账户 + 与通知助手区分」
- [x] `WalletDetailTypeEnum` 含 `PRODUCT_PAY` / `PRODUCT_REFUND` / `PLATFORM_IN` / `PLATFORM_OUT`；`PRODUCT_FROZEN` / `PRODUCT_UNFROZEN` 仍在（`@Deprecated` + 历史读取说明）
- [x] 枚举顺序/描述与 spec「流水类型」段一致：结算与退款都落 `PLATFORM_OUT`，方向由订单状态与配对行判定（写进 `PLATFORM_OUT` 的注释，别写进代码分支）
- [x] 本票**只**改上述三个文件；不碰仓储 / mapper / 用例 / 文档（文档是票 05，仓储是用例票 02）
- [x] 编译闸门：**不运行 mvn / 任何测试**（spec 交付口径由维护者自行验证）；改动只增符号与改注释，不引入未定义引用

**落地注记（实现取舍）：**

一、**票里自相矛盾的地方，按「只改 3 个文件」执行**。What to build 第 4 条写「检索全仓 `WalletDetailTypeEnum` 的枚举常量引用，只改注释里的类型名」，但验收清单第 4 条写「本票**只**改上述三个文件；不碰仓储 / mapper / 用例 / 文档」，加上用户口径「严格只改票里列的 3 个文件」——以后者为准。因此全仓仍在写 `PRODUCT_FROZEN` / `PRODUCT_UNFROZEN` 的**注释**（`IdleCase:45,231-237,281`、`IWalletRepository:91`、`WalletPayTaskEntity:15`、`WalletRepository:328`、`WalletDetailMapper.xml:30`、`schema.sql:818,849`、`docs/db/*`、`docs/api/*`）**原样留给票 02 / 票 03 / 票 05**，本票一个都没碰，避免并发 worktree 冲突。已核对：这些代码引用（`WalletRepository.pay`、`IdleCase` 的解冻/扫描分支、`WalletIncomeFailureProbe`）引用的都是被保留的枚举常量，本票改动后仍可编译。

二、**枚举顺序：现行类型按 spec 顺序，废弃类型垫底**。最终声明顺序为 `RECHARGE / FROZEN / UNFROZEN / INCOME → PRODUCT_PAY / PRODUCT_REFUND / PRODUCT_INCOME → PLATFORM_IN / PLATFORM_OUT → PRODUCT_FROZEN / PRODUCT_UNFROZEN(Deprecated)`。把两个废弃类型从原位置（`PRODUCT_INCOME` 之前）挪到末尾是本票唯一「超出字面」的结构改动，依据是：类型按 `name()` 落库（`WalletRepository:328` 写 `.name()`，`:217` 用 `valueOf` 读回），全仓无 `ordinal()` 调用、无 `values()` 依赖、无 `switch` 穷举，所以顺序不承载语义。好处是读代码时不会把「已不再写入」的类型误当现行类型。`PRODUCT_INCOME` 语义不变（卖家入账），只是按 spec 顺序被夹在 `PRODUCT_REFUND` 与 `PLATFORM_IN` 之间。

三、**`PLATFORM_OUT` 的方向只写注释、不进代码**。按票要求，结算与退款共用本类型，方向由订单状态与配对行（`PRODUCT_INCOME` / `PRODUCT_REFUND`）判定——已写进枚举常量的 Javadoc，没有加 `direction` 字段、没有加布尔参数，留给票 02 在仓储里判。

四、**常量取值 `.0002` 紧跟系统助手 `.0001`**，保持 36 位 UUID 形态；没用全零尾号（`...0000`）以免与「空 UUID / 占位」语义混淆。`public final static` 与文件既有风格一致，未改成 `static final`。

五、**`WalletPayTaskStateEnum` 顺手修了两处同义旧词**（不改代码）：票里只点名类注释那句「与支付单 PAID、PRODUCT_FROZEN 流水、余额扣减一起提交」，但同段紧接着的「行在 ⇒ 钱已冻且支付单已 PAID」和 `PENDING` 的「冻结已提交」若不动，新句子会和紧邻旧词直接打架。两处改成「买家出账并落到平台账户 / 买家出账 + 平台入账已提交」。这是同文件、同义的注释一致性修正，属票的意图内。

六、**引用了一个尚不存在的文档路径**：两个 `@deprecated` 的 Javadoc 按票面指向 `docs/adr/0019-platform-escrow-account.md`（ADR 目录现到 `0018`，0019 由票 05 新建）。若票 05 最终换了文件名，需要回来改这两处 Javadoc。

七、**测试：按 tdd 判断没有值得写的测试**。本票的产物全是编译期符号（一个保留常量 + 4 个枚举常量）与注释，没有任何可观察的运行时行为变化——唯一能断言的「`PLATFORM_USER_ID` 存在且 ≠ `SYSTEM_USER_ID`」「枚举含 4 个新常量」都是把声明再抄一遍的重言式测试（tautological），过不了「预期值来自独立事实源」这条。真正的行为缝在 spec 已认账的两处：用例编排层 `IdleCase`（三方余额 + 订单状态 + 流水行）与钱包仓储事务（双边同生共死），分别由票 02 / 票 03 落地。按用户交付口径也未运行 `mvn`/任何测试，只做了静态核对：3 个文件的符号定义完整、无未定义引用、文件外既有引用仍能解析。
