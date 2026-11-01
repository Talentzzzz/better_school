# errand + wallet/credit/user/moderation：事务下沉

Type: task
Status: resolved
Blocked by:

## Question

都是同一种机械活：把域服务里 `transactionTemplate.execute(...)` 的整段原样搬成该域仓储的方法，仓储实现里用 `TransactionTemplate` 包住；域服务去掉 `TransactionTemplate`。状态机守卫、条件 UPDATE 的 0 行处理、幂等位、流水先行的语义**一律不变**。

- **errand**（9 处）：`ErrandOrderServiceImpl:53`（下单 + 超时注册）、`:112`（支付转待审 + ERRAND_PAID outbox）、`:179`（交付）、`:270`（申诉）、`:314`（仲裁）、`:456`/`:467`/`:478`/`:489`（确认/取消/超时/自动完成 + 搜索同步）。
- **wallet**（4 处）：`WalletAccountServiceImpl:32/50/68/81`（recharge / freeze / unfreeze / income）。
- **credit**（4 处）：`CreditAccountServiceImpl:52/88/99/114`（流水先行 + 调分 + 封禁 + 回读）。
- **user**（1 处）：`UserAuthServiceImpl:43`（save + 注册 outbox + 搜索同步 outbox）。
- **moderation**：0 事务、1 仓储，只核对没有注入别的东西（`KeywordScreenServiceImpl` 的落位问题见 map「Not yet specified」，本票不处理）。

判据：bs 侧编译过；`ErrandFullChainIntegrationTest`、`TradeFullChainIntegrationTest`（钱包/信用分链路）、用户注册相关测试绿；5 个域的 service 不出现 `TransactionTemplate`。

## Answer

**1. errand（9 处 → 9 个仓储原子用例）**

`IErrandRepository` 加「原子用例」段（`ErrandRepository` 内 `TransactionTemplate` 包住，REQUIRED，实现类新增 `transactionTemplate` 字段）：

| 原调用点 | 新 `IErrandRepository` 方法 | 0 行口径 |
| --- | --- | --- |
| `:53` 下单 + 超时注册 | `createWithPayTimeout(orderEntity, payTimeoutMinutes)` | — |
| `:112` 支付 + PAID outbox | `payWithPaidMessage(orderId, userId, timeLimitHours)` | 抛 `BUSINESS_ERROR` |
| `:179` 交付 | `deliverByIdOrThrow(orderId, takerId)` | 抛 `BUSINESS_ERROR` |
| `:270` 申诉 + 搜索同步 | `disputeWithSearchSync(orderId, actorId, actorRole)` | 抛 `BUSINESS_ERROR` |
| `:314` 仲裁 + 搜索同步 | `arbitrateWithSearchSync(orderId, operatorId, cancel)` | 返回 false |
| `:456` 确认 + 搜索同步 | `confirmWithSearchSync(orderId, publisherId)` | 抛 `BUSINESS_ERROR` |
| `:467` 发布方取消 + 搜索同步 | `cancelByPublisherWithSearchSync(orderId, publisherId)` | 抛 `BUSINESS_ERROR` |
| `:478` 超时取消 + 搜索同步 | `markTimeoutWithSearchSync(orderId)` | 返回 false |
| `:489` 自动完成 + 搜索同步 | `autoCompleteWithSearchSync(orderId)` | 返回 false |

- **「0 行抛 vs 0 行返回 false」不是随手定的**：属「状态已被别处改掉 → 本次必须失败」的（支付/交付/申诉/确认/取消）在仓储 lambda 内抛，异常点与回滚边界与改造前逐字一致；属「重试 / 已被其他路径处理」可重入的（仲裁/超时/自动完成，域内在返回 false 后按幂等收口返回 null）保持返回 false。这条规则写进了接口段头注释，不再逐方法叙述。
- **时间戳仍在事务内算**（票 03 的 `action_time` 教训）：`createWithPayTimeout` 传 `payTimeoutMinutes`（域内常量 15）而非算好的时刻，`payWithPaidMessage` 传 `timeLimitHours` 而非算好的 deadline，`LocalDateTime.now()` 都在 lambda 内、且都在对应条件 UPDATE 之前——`TimeoutRegisterOutboxIntegrationTest` 的「action_time ≈ now+15min」与「action_time 原样透传」两条覆盖。
- 域内删掉 `confirmAndSync`/`publisherCancelAndSync`/`timeoutAndSync`/`autoCompleteAndSync`/`disputeAndSync`/`arbitrateAndSync` 六个私有方法；类 javadoc 里「由 case 层在同一事务内编排」是旧口径（违反 `backend/CLAUDE.md` §1 的「禁止 case 层包多域事务」），一并改成「仓储方法自身事务原子，跨域靠幂等 + 重试」。

**2. wallet（4 处）**：`recharge`/`freeze`/`unfreeze`/`income` 的 `transactionTemplate.execute` 原样搬进 `WalletRepository` 同名方法（`insertDetail` + `updateXxxBalance` 留在 lambda 内，`updateFreeze==0` 仍抛 `PARAM_ERROR("余额不足，无法完成操作")`）；`IWalletRepository` 的「事务边界由 wallet 域服务管理」改为「每个资金动作由仓储方法自身用事务（REQUIRED）收成一个原子用例」；`WalletAccountServiceImpl` 四个重载只剩转发。

**3. credit（4 处）**：`applyChangeWithDetail` / `adjustByAdminWithDetail` / `banByAdminWithDetail` / `unbanByAdminWithDetail` 落 `CreditRepository`——流水先行（撞 uk 返回 null = 幂等跳过）、调分、低分封禁、回读四步全在 lambda 内；封禁/解封的「0 行且已在目标态 = 幂等成功，否则抛 `BUSINESS_ERROR`」原样留在 lambda 内。`STATE_NORMAL`/`STATE_BANNED` 提成实现类私有常量（不泄漏到 domain）。`adjustByAdminWithDetail` 只收流水实体、`delta` 由 `creditDetailEntity.getChange()` 反读，避免同值两参数各说一套。`CreditAccountServiceImpl` 的每日封顶预检（`sumTodayChange`）是事务外只读，保持在域内。

**4. user（1 处）**：`IUserRepository.save`+`saveRegisterMessage`+`saveSearchSyncMessage` 三个方法合成一个 `saveRegistered(userEntity)`——insert + `bindBizId` + 回填 `userId` + 注册 outbox + 搜索同步 outbox 全在一个仓储事务内，返回回填后的实体（与改造前同一个 `UserEntity.create(...)` 实例）。被合并掉的三个方法除 `UserAuthServiceImpl` 外零调用方，故直接从接口摘除（不是「废弃面保留」）。验证码校验（Redis）与重复手机号检查仍在事务外。

**5. moderation（核对）**：`domain/moderation` 与 `infrastructure/.../moderation` 内 `TransactionTemplate`/`transactionTemplate`/`execute(` 0 命中；`ReviewServiceImpl:21` / `ReportServiceImpl:19` 只注入 `IModerationRepository`，`ModerationRepository` 只注入 `IModerationDao` + `ObjectMapper`——无额外注入、无事务，`KeywordScreenServiceImpl` 落位按票面不动。

**判据结果**

- `mvn -o compile -pl bs-domain,bs-infrastructure,bs-case,bs-trigger,bs-app -am`：BUILD SUCCESS。
- 定向：`ErrandFullChainIntegrationTest`(4) + `TradeFullChainIntegrationTest`(9) + `TimeoutRegisterOutboxIntegrationTest`(3) + `AuthorProfileIntegrationTest`(11，注册链路) = 27 项绿。
- 全量 `mvn -o test -pl bs-app -am`：**230 项 0 失败 0 错误**。
- `grep TransactionTemplate` 在 `bs-domain` 0 命中（连 import 都没有）；5 个域的域服务构造参数只剩自己的仓储。

**执行中的偏差（有意，非票面原文）**

- 票面写「搬成该域仓储的方法」，实现上把**6 处返回 boolean 的私有方法改成 `void` + 仓储内抛**（`confirmAndSync` 等）：改造前 0 行时 lambda 返回 false、事务空提交后由域内抛；现在 0 行时在 lambda 内抛、事务回滚。两者「0 行」时都没有任何已写数据，回滚与提交不可区分，异常类型/`ResponseCode`/文案逐字一致——与票 03 复查结论同口径（`editOnSaleContentWithSearchSync` 也是「0 行即抛」）。
- `deliverByIdOrThrow` 的名字带 `OrThrow` 后缀：接口里已有返回 boolean 的 `deliverById`（仓储内部复用），不重名无法共存；同批另外三个抛异常的方法用 `*WithSearchSync` 命名，命名族不完全统一已在接口段头注释用统一规则兜住。
- 三个域服务/仓储的类 javadoc 与段头注释按票 01–03 的先例补齐（`CreditRepository`/`WalletRepository` 原先没有类 javadoc）。

**code-review 两轴复查（Standards + Spec）结论**

- Spec 轴（逐点 before/after 等价性）：18 处事务 + moderation 逐一比对，**无行为缺陷**；最硬的 `payWithPaidMessage`（`ErrandTimeLimitVO.fromHours` 是否移出事务）确认无副作用、同一个 int 进 `plusHours`、`now()` 仍在事务内同位置。唯一「跨事务边界挪了求值点」的是 credit 三处的 `CreditDetailEntity.create(...)` 时间戳（现于调用点算、原先在 lambda 内算）——值只算一次、库内不读时钟，惰性无害。
- Standards 轴：分层/命名/死代码/未用 import 全清；提的 6 条已修 4 条（credit 冗余 `delta` 参数、接口段头统一 0 行规则、`TransactionTemplate` 不写进 domain 接口注释、wallet/user 的「原子用例」注释移到 `execute` 之上）、另 2 条（`deliverByIdOrThrow` 命名、`ITradeRepository` 同款「仓储面保留粗粒度方法」）为跨票一致性判断，按票 03 先例保留不改。

**遗留（不在本票）**

- `domain/errand|wallet|credit|user` 的仓储接口仍保留粗粒度单语句方法（`save`/`payById`/`insertCreditDetail`/`ensureWallet`…），只被仓储自身的新原子方法调用；票 03 的 `ITradeRepository` 同款，属接口面收窄的独立议题。
- `KeywordScreenServiceImpl` 落位（`infrastructure/adapter/screen/`）与 moderation 域分包不一致，见 map「Not yet specified」。
- 触发层/case 层直连仓储未收口（map 清单内，本票不动）。
