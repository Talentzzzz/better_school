# 收口：规约落位 + 三工程编译 + 集成测试

Type: task
Status: resolved
Blocked by: 01, 02, 03, 04, 05

## Question

1. **规约落位**：把 map 的规则写进 `backend/CLAUDE.md` —— §1 删掉「例外：只读数据访问（chat 校验目标用户存在等）可在域内注入其他域仓储接口」，改为「只注入本域仓储与本域 port，跨域只读经消费方域定义的 port、实现落 infrastructure」；§2/§3 补「一个业务域恰一个 `I<域>Repository`（实现内可含多个 DAO）、事务在仓储内用编程式事务（REQUIRED）、域服务不注入 `TransactionTemplate`」。同步矛盾的 javadoc：两个 `TransactionConfig`，以及 `IChatRepository` / `IPointsRepository` / `IDrawCountRepository` / `IVirtualInventoryRepository` / `IPhysicalQuotaRepository` 里「事务边界由调用方（域服务）圈定」的说法。
2. **验收**：起常驻栈（`powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/dev-stack.ps1 up`）→ 三工程各自 `mvn -o clean package -DskipTests` → 跑集成测试（`mvn test -pl bs-app -am`；marketing-app、timeout-center-app 同法）。
3. **反查**（结果写进 Answer）：每个域恰一个 `I*Repository` 接口；`domain/**` 不 import 他域 `adapter.repository`、不出现 `TransactionTemplate`；`case/**`、`trigger/**` 不出现 `TransactionTemplate`。

判据：上面全绿。残余问题（如触发层直连仓储）记进 map 的「Not yet specified」，不就地扩范围。

## Answer

**1. 规约落位（`backend/CLAUDE.md`，只改这一个文档）**

- **§1 表格 `xxx-domain` 行**：`不注入其他域 service/repository` → `**只注入本域仓储与本域 port**，不注入他域 service/repository、不注入 TransactionTemplate（跨域动作返回结果对象；跨域只读经本域 port）`。
- **§1 约束「domain 服务禁止互相注入」条**：删掉结尾的「例外：只读数据访问（chat 校验目标用户存在等）可在域内注入其他域仓储接口」，改写为 **「跨域只读一律经消费方域定义的 port」**——需要读他域数据时，在**消费方域**的 `domain/<X>/adapter/port/…` 定义 port（判据留在消费方域，port 里不放判据逻辑），实现落 `infrastructure/adapter/port/<域>/`（实现内可只读他域仓储）；域层只注入本域仓储 + 本域 port、不再注入他域仓储、不再注入 `TransactionTemplate`。
- **§1 新增两条约束**：**一域一仓储**（每域恰一个 `I<域>Repository` 接口与一个实现，读写同接口，实现内可注入多个 DAO，PO/mapper XML/DAO 不因合并而变）；**事务落位在仓储内**（域服务只调一次仓储方法；仓储实现内用 `TransactionTemplate`、传播恒 `REQUIRED`，与调用方事务合流；理由是取号序/原子性依赖 REQUIRED）。
- **§2 包结构**：`adapter/repository/` 行标注「`I<域>Repository`（一域恰一个，读写同接口）」、`adapter/port/` 行注明用途；包结构后补两段「一域一仓储」「事务落位在仓储内」说明。
- **§3 规则**：补两条 —— 一域一仓储（`XxxRepository implements I<域>Repository`，实现内可注入多 DAO + 共享 outbox/Redisson，PO/XML/DAO 不动）；事务在仓储实现内（`TransactionTemplate` REQUIRED 收成原子用例；域服务不注入；**每工程一个事务 Bean `<工程>-app/.../config/TransactionConfig.java`，仅为仓储提供模板**）。

**2. javadoc 同步（票面点名项逐条核对）**

| 票面点名 | 处置 |
| --- | --- |
| `better-school/bs-app/.../config/TransactionConfig.java` | **本票改动**：原「编程式事务模板：领域服务内用 transactionTemplate.execute 包裹业务与 outbox 写入」→「**仓储实现**内用 transactionTemplate.execute 把一次操作的多个 DB 副作用收成一个原子用例（chat 取号+落消息+扇出时间线+推送 outbox；trade 商品状态变更+搜索同步；community 审核通过后计数增减；notification 取号+落库），传播恒 REQUIRED……**域服务不再注入本模板**」 |
| `marketing/marketing-app/.../config/TransactionConfig.java` | 票 05 已同步（已写「域服务不再注入本模板」），本票无需改 |
| `IChatRepository` / `IPointsRepository` / `IDrawCountRepository` | **票 01/05 执行时已同步**：grep「事务边界由调用方」「圈定」在 `backend/**/*.java` **0 命中**；三接口 javadoc 均已是「仓储内 REQUIRED、域服务不再自持事务」新口径 |
| `IVirtualInventoryRepository` / `IPhysicalQuotaRepository` | 票 05 已合并为 `IInventoryRepository`，其 javadoc 已是新口径。顺手修掉同区域 2 处残留旧口径注释：`IPhysicalQuotaDao:16`「假定在**域服务**的事务内调用」→「假定在**仓储方法（InventoryRepository）**的事务内调用」；`InventoryRepository:33`「分叉语义……留在 mapper SQL 与**域服务**里」→「……留在 mapper SQL 与**该仓储方法的写入顺序**里」 |
| （顺手）`NotificationRepository:63` | 「事务边界与原先在域服务里的一致（PROPAGATION_REQUIRED）」→「事务传播恒 REQUIRED（调用方已有事务则并入，无则本行自成一个事务）」 |

全仓 grep 复核：`域服务` + `事务|template` 的剩余命中均为新口径或与事务无关（`trade`/`chat` 仓储的「与域服务事务同传播语义」、case 的「各自事务原子」等）。

**3. 验收**

- **起常驻栈**（证据日志 `.scratch/repo-per-domain/06-stack.log`）：`powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/dev-stack.ps1 up` —— 中间件就绪（`bs-mysql` / `bs-redis` / `bs-minio` / `bs-nacos` / `bs-nacos-test` / `bs-rocketmq-namesrv` / `bs-rocketmq-namesrv-test` healthy；`bs-rocketmq-broker` / `bs-xxl-job-admin` 无 healthcheck、running），dev 三库灌好（表数 `better_school 29` / `bs_marketing 22` / `bs_timeout 16`）；脚本第 6 步自带三工程 `mvn -o clean package -DskipTests`，三次 `[ok]`。
  - **偏差（不影响验收）**：脚本第 7 步起三服务时 `Start-Process` 报 `HTTP_PROXY`/`http_proxy` **键名大小写冲突**（PowerShell `Start-Process` 的已知行为），三个 app 进程未起来、脚本最终 `exit=1`。集成测试不需要这三个进程：测试在测试 JVM 内自起 Spring 上下文（`@SpringBootTest(webEnvironment = RANDOM_PORT)`），只连常驻栈的 `*_test` 中间件；marketing 侧对单体的 Dubbo 依赖另有 `integration/support/StubUserQueryPort` 顶掉。测试全绿即为证。
- **三工程编译**：`mvn -o clean package -DskipTests` 三个工程各一次，**全部 BUILD SUCCESS**（`better-school` / `marketing` / `timeout-center`）。
- **集成测试**：`mvn -o test -pl <工程>-app -am` 三个工程各一次 —— **bs 230 项 / marketing 82 项 / timeout-center 37 项，全部 Failures 0、Errors 0、Skipped 0**，三次 `BUILD SUCCESS`、退出码均为 0。
  - 明细：bs 230（含 `Trade*` 6 类 35 项、`AuthorProfileIntegrationTest` 11、`WebHandlerVirtualThreadIntegrationTest` 1、chat 单测 11）；marketing 82（`RaffleDrawPipeline` 11 / `RaffleQuery` 10 / `RaffleShippingConsumer` 7 / `RaffleShippingOutbox` 5 / `RaffleChain` 5 / `RaffleWarmup` 3 / `PointsDeduct` 5 / `DrawCountBucket` 6 / `VirtualInventory` 5 / `PhysicalQuota` 5 / `InventoryFault` 2 / `MarketingConfig` 5 / …）；timeout-center 37。
  - 测试日志里可见 `No route info of this topic: bs-user-register` / `bs-search-sync` 类 WARN（outbox 异步发送，测试库未预建 topic）——与票 01~05 一致，属「MQ 不可达仅日志、不影响被测行为」，未影响任何断言。

**4. 反查（4 项全绿，脚本化实测）**

| 检查 | 结果 |
| --- | --- |
| 每域恰一个 `I*Repository` | **OK**。better-school 11 个域中 10 个恰 1 个仓储（`chat`/`community`/`credit`/`errand`/`moderation`/`notification`/`search`/`trade`/`user`/`wallet`），`domain.file` 为 0（只定义 port、无持久化，map 现状表已记）；marketing 5 个业务域各 1（`config`/`drawcount`/`inventory`/`points`/`raffle`），`domain.common` 为 0（共享段、非业务域）；timeout-center `domain.task` 1 |
| `domain/**` 不 import 他域 `adapter.repository` | **OK**。三工程 domain 层 `src/main` 的 `adapter.repository` import 共 37 处，**全部同域**，跨域 **0**（domain 测试源另有 1 处 `TimeoutTaskScanServiceTest`，同为 task 本域） |
| `domain/**` 不出现 `TransactionTemplate` | **OK**。`import org.springframework.transaction.*` **0**；`private final TransactionTemplate` **0**；`transactionTemplate.execute*` 调用 **0**。`marketing-domain` 内 9 处 `TransactionTemplate` 命中**全是 javadoc 文字**，且都在说「由仓储自己用（REQUIRED）」「域服务不再注入」 |
| `case/**`、`trigger/**` 不出现 `TransactionTemplate` | **OK**。三工程 case + trigger 层 **0 命中**（含 import / 字段 / 调用） |

**5. 残余（已按票面写进 map 的「Not yet specified」，不在本票扩范围）**

- 触发层 / case 层直连仓储共 **10 处（全部在 bs 侧，marketing / timeout-center 为 0）**，实测名单已替换 map 里的旧名（旧名 `IPostRepository` / `IProductRepository` 已被票 02/03 合并掉）：
  - `case`：`moderation/ReportCase→IChatRepository`；`community/CommentReviewCase`、`community/PostPublishCase`、`errand/ErrandOrderCase`、`trade/TradePublishCase`、`user/UserRegisterCase → IUserRepository`（5 处）。
  - `trigger`：`http/ChatController→IUserRepository`、`ws/ChatWsPushService→IChatRepository`、`listener/PostCountConsumer→ICommunityRepository`、`listener/ProductCountConsumer→ITradeRepository`。
- 其余三条（inventory 合并后 Redis 桶与台账是否再分层、moderation `KeywordScreenServiceImpl` 落位、可选架构守护测试）维持 map 原文，本票不加。

**6. code-review 两轴复查（Standards + Spec）发现并处理**

| 轴 | 发现 | 处理 |
| --- | --- | --- |
| Standards | 本票顺手改的 `InventoryRepository:33`（「分叉语义……留在域服务里」→「……与仓储方法的写入顺序里」）与本域接口 `IInventoryRepository:18`、`PhysicalQuotaServiceImpl:20` 形成**三方口径不一致**（后两处仍写「分叉语义留在域服务里」），且该文件改动不在票面清单内 | **整文件回退** `InventoryRepository.java`（`git checkout`）——这句话讲的是分叉语义、不是事务落位，改它属扩范围；回退后三处口径重新一致。票面点名的事务口径注释仍改（`IPhysicalQuotaDao:16`） |
| Standards | `ChatQueryServiceImpl:25` 仍写「用户资料经 chat 自有 port 只读他域（**规范允许的例外**）」——「例外」框架已被本票删掉（跨域只读走自有 port 现在就是**规则本身**） | 改为「……只读他域——跨域只读一律走消费方域自有 port（CLAUDE.md §1），**不是「例外」**」 |
| Standards | Judgement call：`一域一仓储` / `事务落位在仓储内` 在 §1/§2/§3 近乎逐字重复 6 次（票面本身要求 §2/§3 补，属标准覆盖基线，不下判违规） | §2 两段压成一段并指回 §1（保留树上的行内标注），减少逐字重复 |
| Spec | Answer 残余清单把 `ReportCase` 标成 `case/community/…`，实际在 `usecase/moderation/ReportCase.java` | 本票 §5 与 map 的 Not yet specified 均改为 `case/moderation/ReportCase` |
| Spec | Answer 称 domain 层仓储 import「37 处」，未限定 `src/main`（含 domain 测试源实测 38 处） | Answer 改为「`src/main` 37 处；domain 测试源另有 1 处，同为 task 本域」，结论不变 |
| Spec | 「起常驻栈」只有叙述、无证据文件 | 重跑 `dev-stack.ps1 up` 并把完整输出留档 `.scratch/repo-per-domain/06-stack.log`（日志里同时可见第 6 步三工程编译 `[ok]`） |
| Spec | 三处「顺手」javadoc（`NotificationRepository:63`、`InventoryRepository:33`、`IPhysicalQuotaDao:16`）不在票面点名清单 | 均为注释、不改行为；`NotificationRepository:63`（旧口径「与原先在域服务里的一致」）与 `IPhysicalQuotaDao:16`（「假定在**域服务**的事务内调用」）确属「矛盾的 javadoc」，保留；`InventoryRepository:33` 按上表回退 |

**判据结果**：规约落位（§1/§2/§3 + 矛盾 javadoc）✅；三工程编译 ✅；集成测试 349 项 0 失败 ✅；反查 4 项 ✅。两轴复查无「硬违规」残留项。
