# 一域一仓储与仓储内事务（三工程改造）

Effort 目录：`.scratch/repo-per-domain/`　标签：`wayfinder:map`

## Destination

三个工程（better-school / marketing / timeout-center）的每个业务域**恰有一个** `I<域>Repository` 接口与一个实现：读写同接口、实现内可注入多个 DAO；domain 层只注入**自己的仓储 + 自己的 port**，不再注入他域仓储、不再注入 `TransactionTemplate`；需要事务时由**仓储方法自身**用编程式事务（REQUIRED）把一次操作收成一个原子用例；`backend/CLAUDE.md` 反映新规约；三工程编译过、相关集成测试绿。

> 执行被明确拉进 map 内（用户选择「全仓改造完成」，且要求「怎么快怎么来、别做复杂」）：6 张票全是执行票，一张一 session（200k 内）。

## Notes

**范围与口径（grilling 已定，执行时不再重开）**

1. 范围＝三个工程全改，顺序 better-school → marketing → timeout-center（timeout-center 已合规，无票）。
2. **一域一仓储**：严格一个接口 + 一个实现，读写同接口。合并后取 `<域名>Repository`：community→`ICommunityRepository`、trade→`ITradeRepository`、raffle→`IRaffleRepository`、inventory→`IInventoryRepository`；已是单仓储的域**不改名**（chat / user / errand / moderation / credit / wallet / search / points / drawcount / config / timeout task）。
3. **跨域只读** → 消费方域在 `domain/<X>/adapter/port/…` 定义 port，infrastructure 实现（实现内可只读他域仓储）。domain 层从此只认识「自己的仓储 + 自己的 port」。
4. **事务** ＝ 仓储方法即一个原子用例：域服务构造参数/实体、调一次仓储方法；仓储实现内用 `TransactionTemplate` 完成多 DAO 读改写。域服务不再注入 `TransactionTemplate`。
5. **DAO 不做归属约束**：仓储实现可注入任意 DAO；共享 outbox（`OutboxMessageStore` / `TimeoutRegisterOutboxStore` / marketing `common/outbox`）保持现状。唯一要迁的 DAO 是通知的（票 01）。
6. **通知升为独立域** `domain/notification`：其它域发通知只经 case 层调其 service（票 01）。
7. **文档只改 `backend/CLAUDE.md`**：删 §1「只读可注入他域仓储」例外；§2/§3 补事务落位与一域一仓储。**不写 ADR**（票 06）。
8. **测试代码豁免**：可直注多仓储、可自持 `TransactionTemplate` 造数，只跟随 API 变化。

**执行口径从简（三条，别做复杂）**

- **搬事务** ＝ 把域服务里 `transactionTemplate.execute(...)` 的整段 lambda 原样搬成一个仓储方法，仓储实现里用 `TransactionTemplate` 包住；域服务删掉注入。**不趁机重构、不强制封装聚合对象、不加新抽象**。
- **合并仓储** ＝ 把被合并实现类的方法体原样搬进合并后的类（该类持有多个 DAO 字段）；**PO、mapper XML、DAO 一律不动**（通知的 DAO 归位是唯一例外）。
- **跨域只读** ＝ 一个消费方域按需要几个方法就几个方法，**不为每个调用点造 port，port 里不放判据逻辑**（判据留在消费方域）。

**必须保持的不变量（改造中不可破）**

- 仓储内事务与既有域服务事务靠 **REQUIRED 传播合流**（`NotificationRepository.insertOne` 的取号语义、`DrawRecordRepository`、`OutboxTransactionProbe` 探针）：事务挪进仓储后必须仍是 REQUIRED，否则取号序/原子性被破坏。
- 「同事务写 outbox」口径不变；`CONTEXT.md` 词条口径（如评论计数随审核通过与软删同事务增减）即验收标准。
- **业务行为零变更**：拒绝路径、幂等键、时序、HTTP/Dubbo 契约一律照旧。

**改造前先读**：`CONTEXT.md`（领域词汇）、`backend/CLAUDE.md`（分层与包结构）、`backend/docs/testing.md`（跑测试入口）。

**现状速览（实测，执行时按此定位）**

| 工程·域 | 仓储接口数 | 出向跨域只读 | 域服务事务点 | 票 |
| --- | --- | --- | --- | --- |
| bs·chat | 2（含通知） | 4（user/post/product/errand，3 文件） | 6 | 01 |
| bs·search | 1 | 4（post/product/errand/user，1 文件） | 0 | 02 |
| bs·community | 2 | 1（user，2 文件） | 12 | 02 |
| bs·trade | 2 | 0（1 处同域跨聚合） | 11 | 03 |
| bs·errand | 1 | 0 | 9 | 04 |
| bs·user / credit / wallet | 各 1 | 0 | 1 / 4 / 4 | 04 |
| bs·moderation | 1 | 0 | 0 | 04 |
| bs·file | 0（只有 port） | 0 | 0 | — |
| marketing·raffle | **4**（共享 1 个 `IRaffleDao`） | 0（跨域走 4 个 port） | 1（+仓储内 2） | 05 |
| marketing·inventory | 2 | 0 | 2 | 05 |
| marketing·points / drawcount | 各 1 | 各 1（config） | 3 / 2 | 05 |
| marketing·config | 1 | 0 | 0 | 05 |
| timeout·task | 1 | 0 | 0 ✅ 已合规 | — |

- `@Transactional` 全仓 0 命中；事务 Bean：`bs-app/.../config/TransactionConfig.java:15`、`marketing-app/.../config/TransactionConfig.java:17`。
- 事务点 71 处：约 45 处在域服务（本次改造对象）、6 处已在仓储实现里（保持）。
- 唯一「基础设施层跨域仓储」：`NotificationRepository:26 → IUserRepository`（广播事务内全量扫用户），票 01 处理。
- `SearchRepository` 落位在 `adapter/search` 违反 §3（票 02 归位）。

## Decisions so far

<!-- 每张票 resolve 后追加一行：要点 + 链接 -->

- **01 通知拆域 + chat 域改造**（resolved）：`domain/notification` 独立成域（`INotificationService` / `INotificationRepository` + 域内 `IBroadcastRecipientPort` 取代 infra 唯一跨域仓储引用），`dao/notification` 与 mapper namespace 归位；chat 跨域只读收成 `IUserQueryPort` / `ICardRefQueryPort`，4 组事务搬进 `IChatRepository` 原子用例（仓储内 REQUIRED）。bs 包构建过、4 个必测类 + 全量 230 项绿。见 [issues/01-notification-and-chat.md](issues/01-notification-and-chat.md)。
- **02 search 跨域只读收口 + community 两仓储合一**（resolved）：search 四个他域仓储注入收成一个自有 port `ISearchQueryPort`（12 方法，实现 `adapter/port/search/SearchQueryPort`，只取实体、判据留域内），`SearchRepository` 归位 `adapter/repository/search/`；community `IPostRepository`+`IPostCommentRepository` 合一为 `ICommunityRepository`（`CommunityRepository` 持两 DAO，同名方法加域限定消歧），`IUserRepository` 只读改域内 `IUserQueryPort`/`CommunityUserVO`，12 处事务原样搬成仓储方法（REQUIRED）。构建过、3 个必测类 + search 重建/游标链路的 4 个类 + 全量 230 项绿。见 [issues/02-search-and-community.md](issues/02-search-and-community.md)。
- **03 trade 两仓储合一 + 事务下沉**（resolved）：`IProductRepository`+`IProductOrderRepository` 合一为 `ITradeRepository`（实现 `TradeRepository` 落位 `adapter/repository/trade/`，持 `IProductDao`+`IProductOrderDao`+共享 outbox，PO/XML/DAO 零动），订单侧 7 个同名方法加 `Order` 限定消歧；域服务 11 处事务原样搬成仓储方法（5 处跨表原子用例 + 6 处商品状态变更带搜索同步，REQUIRED），`domain/trade` 只注入 `ITradeRepository`、`TransactionTemplate` 归零；`ProductCountConsumer` 与 search/chat 两个只读 port 改引 `ITradeRepository`。构建过、必测两类 12 项 + 全量 230 项绿。见 [issues/03-trade.md](issues/03-trade.md)。
- **04 errand + wallet/credit/user/moderation 事务下沉**（resolved）：errand 9 处、wallet 4 处、credit 4 处、user 1 处 `transactionTemplate.execute` 的整段 lambda 原样搬成各自仓储的原子用例（仓储实现内 REQUIRED 包裹），域服务删掉 `TransactionTemplate` 注入（`bs-domain` 0 命中）；errand「0 行即抛」与「0 行返回 false」两分口径写进接口段头注释，时间戳仍在事务内算（票 03 教训）；user 侧 `save`+注册 outbox+搜索同步 outbox 三方法合成 `saveRegistered`；credit 的 `delta` 只走流水实体一个源。moderation 核对为 0 事务、无额外注入。定向 27 项 + 全量 230 项绿。见 [issues/04-errand-and-small-bs.md](issues/04-errand-and-small-bs.md)。
- **05 marketing 三域一仓储 + 事务下沉 + config 只读改 port**（resolved）：raffle 四接口合一为 `IRaffleRepository`（实现 `RaffleRepository`，持 `IRaffleDao`+Redisson+outbox+`TransactionTemplate`，两处仓储内事务连 lambda 原样搬）、inventory 两接口合一为 `IInventoryRepository`（虚拟侧 10 方法加 `Virtual` 消歧，`merge*BucketGroup`/`confirmIssued` 搬进仓储）、points/drawcount 五个事务 lambda 搬成仓储方法（造桶含时点与有效期读一并下沉），config 三处跨域只读改成 `IPointsConfigPort`/`IDrawCountConfigPort`/`IRaffleConfigPort`（实现落各域 infrastructure）；兑换边按全仓口径收敛——抽奖域只扣分（返回 `RedeemIntentVO`），次数入账由新增 `RaffleExchangeCase` 顺序调两个域方法，幂等靠共用兑换单号 + 重试。构建过、全量 82 项绿（票面点名 8 类在内）。复查抓到并修掉 1 处真实 bug（预热态 key 丢 `strategyId`）+ 1 处时点算在事务外 + 4 处过期文档。见 [issues/05-marketing.md](issues/05-marketing.md)。
- **06 收口：规约落位 + 三工程编译 + 集成测试**（resolved）：`backend/CLAUDE.md` §1 表格 `xxx-domain` 行与「领域嵌套」条删掉「只读可注入他域仓储」例外、改为「跨域只读经消费方域 port、实现落 infrastructure；只注入本域仓储 + 本域 port」，§1/§2/§3 补「一域一仓储」「事务在仓储内（REQUIRED）、域服务不注入 `TransactionTemplate`」；矛盾 javadoc 同步——`bs-app/TransactionConfig` 改为「仓储实现内」（marketing 侧票 05 已改），票面点名的 `IChatRepository`/`IPointsRepository`/`IDrawCountRepository` 经 grep 证实票 01/05 已同步（0 命中「事务边界由调用方」），`IVirtualInventoryRepository`/`IPhysicalQuotaRepository` 已并入 `IInventoryRepository`（顺手修 `IPhysicalQuotaDao`/`InventoryRepository`/`NotificationRepository` 三处残留旧口径注释）。起常驻栈（中间件 9 个容器全 up；第 7 步 `Start-Process` 因 `HTTP_PROXY`/`http_proxy` 键冲突未起三 app，不影响测试——测试自起 Spring 上下文、marketing 侧有 `StubUserQueryPort`）；三工程 `mvn -o clean package -DskipTests` 全 SUCCESS；集成测试 bs 230 / marketing 82 / timeout 37 全 0 失败。反查 4 项全绿：每业务域恰 1 个 `I*Repository`（`domain.file` 只 port、marketing `domain.common` 为共享段，各 0 属预期）、`domain/**` 跨域仓储 import 0（37 处全同域）、`domain/**` `TransactionTemplate` 注入/调用 0、`case/**`+`trigger/**` 0 命中。残余「触发层/case 层直连仓储 10 处（bs 侧）」按实测名单留在 Not yet specified。见 [issues/06-closeout.md](issues/06-closeout.md)。

## Not yet specified

- **`dev-stack.ps1` 第 7 步起三服务会失败**（票 06 实测，环境/工具问题、与本次改造无关）：本机同时存在 `HTTP_PROXY` 与 `http_proxy` 两个环境变量时，PowerShell `Start-Process` 组装环境字典会报「字典中的关键字: HTTP_PROXY 所添加的关键字: http_proxy」，三个 app 进程起不来、脚本 `up` 最终 `exit=1`（中间件 1~6 步全 ok）。集成测试不受影响（测试自起 Spring 上下文；marketing 侧有 `StubUserQueryPort`），但要跑 dev/demo 环境需另行处理（如启动前 `Remove-Item Env:\HTTP_PROXY`）。本次按「不就地扩范围」不动脚本。
- **触发层 / case 层直连仓储是否收口**：bs 侧 10 处（票 06 反查实测）——`case/moderation/ReportCase→IChatRepository`、`case/{community/CommentReviewCase, community/PostPublishCase, errand/ErrandOrderCase, trade/TradePublishCase, user/UserRegisterCase}→IUserRepository`（5 处）、`trigger/http/ChatController→IUserRepository`、`trigger/ws/ChatWsPushService→IChatRepository`、`trigger/listener/PostCountConsumer→ICommunityRepository`、`trigger/listener/ProductCountConsumer→ITradeRepository`；marketing / timeout-center 侧 0 处。本次不动。（`NotificationBroadcastConsumer→INotificationRepository` 已按票 01 票面收口到 `INotificationService`，不在本清单内。）
- **inventory 合并后 Redis 桶与台账是否再分层**：桶与台账的读改写落到一个类之后是否需要内部协作者。
- **moderation 的 `KeywordScreenServiceImpl` 落位**（现 `adapter/screen/`）。
- **（可选硬化）架构守护测试**：断言 `domain/<X>` 不 import 他域 `adapter.repository`、域服务不注入 `TransactionTemplate`。本次按「从快」不加。

## Out of scope

- 业务行为与接口契约变更（拒绝路径、幂等口径、时序、HTTP/Dubbo 契约、前端 `front/`）。
- 独立 git 仓库的旧工程 `rafflex-backend/`（`cn.talent` 包、未被本仓库 git 跟踪；其结构看上去已是一域一仓储）。
- 新增 ADR（只改 `backend/CLAUDE.md`）。
- 回改历史 `.scratch/**` 里基于「只读跨域仓储注入」例外做出的 resolved 结论（`ui-revamp-impl/issues/04-author-profile.md:41`、`chat-impl/issues/02-send-pipeline.md:29`）：历史记录保留，新票生效即可。
