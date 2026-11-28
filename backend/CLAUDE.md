# 后端编码规范（DDD 分包）

> 只写会改变行为的规矩。理由与取舍见仓库根 `docs/adr/`、`backend/docs/architecture/`。
> 一个业务域 = `domain/<域>` 一个顶层包，下列各层按域对齐。

## 0. 工程形态

- 唯一 Maven reactor = `backend/better-school/`：七模块 `bs-{types,api,domain,case,infrastructure,trigger,app}` + 单应用 `bs-app`。
- 单库 `better_school`；营销四域（points / draw / rebate / award）与其它域平级的 `com.betterschool.domain.*` 包。
- bean 同名冲突由全局 `FqcnAnnotationBeanNameGenerator` 兜底（组件扫描与 `@MapperScan` 都挂）；`@MapperScan` 单基包 `com.betterschool.infrastructure.dao`。

## 1. 模块与依赖方向（单向上→下，禁反向与平级互依）

```
bs-trigger → bs-case → bs-domain → bs-types
bs-trigger → bs-api、bs-domain、bs-infrastructure
bs-infrastructure → bs-domain
bs-api → bs-types
bs-app → 上述各层（只做启动与 Bean 装配）
```

| 模块                 | 职责                                                       |
| -------------------- | ---------------------------------------------------------- |
| `bs-api`             | 接口 + DTO + `Response<T>`，不依赖业务模块（Dubbo 对外面）  |
| `bs-app`             | 启动类 + `config` 装配；mapper XML 唯一存放处              |
| `bs-domain`          | 领域核心，只依赖 types；只注入本域仓储                     |
| `bs-case`            | 用例编排：跨域顺序调用各域 service，不写业务、不碰事务      |
| `bs-infrastructure`  | DAO / 缓存 / 技术组件，实现 domain 仓储接口                |
| `bs-trigger`         | http / job / listener / ws / rpc 入口                      |
| `bs-types`           | 常量 / 枚举 / 异常 / 消息体                                |

### 域边界

- domain **只注入本域仓储**：不认识他域实体、枚举、service 或 port，不注入 `TransactionTemplate`。
- 跨域读写（含跨域只读）一律由 case 编排，把需要的值作为参数传进本域；本域方法返回「外部动作需求」结果对象（如 `ProductPayResult`）交 case 继续。
- **没有共享域**：`domain` 下只有业务域，不设 `common` 段；各层 `common` 只放非域公共件（统一响应 / JWT / outbox / wire 契约）。**配置项跟表走**——配置表配谁的域就归谁，不设「配置域」。
- **技术能力不开 port**：WS 推送 / Redis / MQ(outbox) 做成自己域仓储上的方法（`IChatRepository.pushMessage`、`INotificationRepository.pushNotif`），仓储实现在 infrastructure 委托技术组件。推送只传本域事件对象、不碰帧信封（组帧收口在 `WsFrameFactory`），一个用户一条全局连接、多业务按帧类型复用（MSG/READ/RECALL/NOTIF）。现存唯一 port 例外：`wallet/adapter/port/IPaymentNotifyPort`（跨域支付回调）。
- **无业务归属的技术能力直接落 infrastructure 技术包、由 trigger 注入**（`file` / `search` / `ws`），不为它建域、不开 case 编排。
- 仓储只对 domain 服务开放：**case 与 trigger 都不得注入 `I<域>Repository`**（含 `@Resource` / 构造器 / 方法参数；现有 `ChatController`、`PingController` 两处是待清理的违规）。case 需要新读写能力时，先在对应域 service 上开方法。

### 仓储与事务

- **一域一仓储**：每域恰一个 `I<域>Repository` + 一个实现（读写同接口）；实现内可注入多个 DAO，PO / mapper XML / DAO 不因合并而变。
- **事务落位在仓储**：域服务把参数/实体备好后只调一次仓储方法；仓储方法**自己在实现里**用编程式事务（`TransactionTemplate`，传播 `REQUIRED`）把多次读改写收成一个原子用例。域服务与 case 都不注入 `TransactionTemplate`、不自持事务边界。事务 Bean 全工程一个（`bs-app/.../config/TransactionConfig.java`）。
- **一个物理事务只写一个域的表**（ADR-0016）。「状态翻转 + 资金动作」走**两段式**：资金段（流水先行 + 唯一键幂等的条件扣减 / 解冻 / 入账）与翻转段（条件 UPDATE 守卫前态）各自本地事务，顺序由 case 保证；段落顺序只允许留**长款**中间态。落单的孤儿由 `productFundOrphanScan`（cron `0/30`）无状态重扫收敛，动作全部唯一键幂等，不新增状态表。
- **跨域一致性 = 幂等 + 重试**，禁止 case 包多域事务：重试安全靠状态机条件 UPDATE、流水唯一键（`wallet_detail.uk_order_type`、`user_credit_detail.uk_user_reason_ref`）、事件流水判定（`product_order_event.event_seq`）。资金 / 信用操作一律**流水先行**（`INSERT IGNORE`，冲突即已处理过），流水表是事实源。失败恢复：MQ 靠 broker 自动重试，HTTP 返回失败让客户端重试。
- **超时注册是唯一跨层例外**：业务仓储在**自己已开着事务的仓储方法**里直调同层技术组件 `TimeoutTaskStore.register(...)`，超时行与业务行同生同死；域服务不注入超时组件。见 [`docs/architecture/timeout.md`](docs/architecture/timeout.md)。

## 2. domain 包结构

```
domain/<域>/
├── model/entity/       XxxEntity（无实体时省略该目录）
├── model/valobj/       XxxVO（含 XxxEnumVO 枚举）
├── service/<子包>/     IXxxService、XxxServiceImpl
└── adapter/repository/ I<域>Repository（一域恰一个，读写同接口）
```

## 3. infrastructure 包结构

```
infrastructure/
├── adapter/repository/<域>/  XxxRepository implements I<域>Repository
├── dao/<域>/                 I<域>Dao（@Mapper）与 <域>/po/；无业务域的表（mq_outbox）落 dao 与 po 根包
├── cache/                    缓存，按域分包
├── merge/                    通用攒批引擎：MergeConfig / MergeEngine / MergeGroup / MergeItem /
│                             MergeResult / MergeStrategy 六件，不认识任何业务表。谁用谁
│                             new MergeEngine(config, strategy) 并在自己的 @PreDestroy 里 close()；
│                             发车口径 = 队列非空即发（无收集窗口，maxBatch 只是单批上界）
├── mergevirtual/             虚拟线程 leader 模型引擎（并存候选，调用方暂不切换），复用 merge 公开类型，
│                             见 `docs/architecture/merge-virtual-leader-engine-spec.md`
├── ws/                       WS 连接机器 + 组件 API（IWsConnectionHub / IWsTicketStore）+ 帧信封（WsFrameFactory）
├── file/                     FileStorage（MinIO 上传 + 白名单，返回公开读 URL）
├── search/                   ISearchIndex（ES）：只认索引名 / 检索字段 / 文档对象；索引名与状态白名单由调用方传，判据留域
├── timeout/                  超时组件：TimeoutTaskStore / Scanner / Settler / DeadLetterAdmin / ShardSlots，动作 SPI = ITimeoutHandler
├── security/                 鉴权
└── gateway/                  外部 http/rpc 接口定义（按需建，当前没有）
```

规则：

- 技术包按需建，不建空包；`adapter/repository` 与技术包平级。
- 依赖单向 **adapter → 技术包**；技术包禁止 import `adapter.repository`。横跨多域、无归属域的写入契约落 `adapter/repository` 根包（先例 `CountActionEnum` / `LikeStateEnum`）。
- 实现类名 = 域接口去掉 `I`，按域分包；实现内可注 `RocketMQTemplate` / `Redisson` 等技术设施。

## 4. trigger 包结构

```
trigger/
├── http/ job/ listener/ ws/ rpc/  跨域与全局入口：XxxController / XxxJob / XxxConsumer / XxxHandler / XxxDubboService
├── <域>/http|job|listener/        域内入口（先例 draw/、points/）
└── config/                        装配与配置面：TimeoutActionRouter、MarketingConfigController 等
```

- Controller 实现 `-api` 里的 `IXxxService`；Dubbo Provider（`@DubboService`）在 `trigger/rpc/`。
- trigger 依赖 case，注入 `IXxxCase`；连接端（`ws/`）可注入 infrastructure 的技术组件契约。
- Dubbo Consumer 侧不落 `domain/adapter/port`：由 case 编排时经他域 service 取回，或由 trigger 注入技术适配。

## 5. case 包结构

```
usecase/<域>/   XxxCase（编排多个域 service）；确需多实现时才开 IXxxCase 接口
```

case 只注入 domain 的 service 接口，编排顺序与跨域取值都写在这里；不写业务规则、不碰事务。**case 之间不互相注入**——跨域顺序各自在本层排完，需要复用就把它下沉成 domain 能力，不要串 case。

## 6. api 包结构

```
api/
├── I<Xxx>Service      接口，Controller / Dubbo Provider 实现它
├── dto/               XxxRequestDTO / XxxResponseDTO
└── response/          Response<T> {code, info, data}
```

按域分子目录（先例 `points/`、`lottery/`、`config/`，各带自己的 `dto/`）；跨域共用接口留在 `api` 根。

## 7. app 包结构

```
com.betterschool/
├── BetterSchoolApplication.java   必须在根包，才能扫到其他模块
└── config/                        线程池 / Redis / MQ / xxljob / Security / MyBatis 等 Bean 装配
```

## 8. types 包结构

```
types/
├── common/     AppException(code, info) 等公共异常、跨域常量与 MQ 消息体
├── exception/  BusinessException、FrameworkException
├── enums/      ResponseCodeEnum(code, info)、OutboxStatusEnum
├── id/         IIdGenerator（String nextId()）+ UuidIdGenerator
├── concurrent/ VirtualThreadFanout
└── <域>/       该域的常量与枚举，如 draw/、points/、rebate/、award/、ws/
```

域内常量枚举放 `types/<域>`，别往 `common` 塞。

## 9. 硬性约定

1. **类名后缀决定分层归属**：Entity / VO / Aggregate / Repository / Dao / PO / Controller / Job / Consumer / Case / DubboService。
2. **接口 `I` 前缀、实现去掉 `I`**：domain 定 `IxxxRepository`，infrastructure 实现 `XxxRepository`。
3. MyBatis mapper XML 统一放 `bs-app/src/main/resources/mybatis/mapper/`（按域分子目录），接口在 infrastructure 的 `dao` 包。
4. 新加业务域：domain 建 `model / service / adapter`（adapter 只放 repository），infrastructure 建 `adapter/repository/<域>` 与 `dao/<域>`；**不建 `adapter/port`**。
5. 命名见根 `CLAUDE.md`：局部变量与参数取类型名首字母小写，字段保持业务裸名；见名知意，`UserAccount userAccount` 不缩写。
6. **业务 ID 一律 UUID 字符串**：对外唯一标识是 36 位 UUID 字符串（DB `VARCHAR(36)`，实体/PO/DTO/mapper 全链路 `String`），由 `IIdGenerator` 在 insert 前生成、随行一次写入。物理主键 `id BIGINT AUTO_INCREMENT` 是纯内部键，永不对外；禁止 `Long`/`bigint` 承载业务 ID，禁止 `useGeneratedKeys` 回写业务列。排序同值用「时间 + 业务 ID」复合键收敛，不拿 UUID 当时间代理；分片/合并路由的哈希输入是业务 ID 字符串本身。
7. **SQL 层零 JOIN**：mapper 里每条语句只做单表读写；跨表展示数据（昵称头像、计数窄表、关系列表的业务行、台账面额等）由仓储层「主查 + 按 id 集批量回查 + Java 拼装」收口，可见性谓词与聚合排序一并挪到仓储 Java 侧。唯一豁免：只读 `NOT EXISTS` 半连接守卫（防重放 / 孤儿判定，先例 `ProductViewCountMapper`、`AwardInventoryDetailMapper`）。
