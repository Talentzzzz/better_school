

# 项目编码规范（DDD 分包分模块约定）

## 0. 当前工程形态（单体收口，monolith-restructure 票 05/06/07）

唯一 Maven reactor = `backend/better-school/`：bs-* 七模块 + 单应用 `bs-app`（`com.betterschool.BetterSchoolApplication`）。
营销四域以**平级领域包**并入 `com.betterschool`（`domain/{points,draw,rebate,award}` 等），
`com.betterschool.marketing` 命名空间退役；数据库收敛为**单库 `better_school`**（营销 20 表并入
`better-school/sql/schema.sql` 尾段，`bs_marketing` / `bs_marketing_test` 库名退役；
原 inventory 域已改名 award 并收窄为单一奖品库存台账）。
同名类的 bean 名冲突由全局 `FqcnAnnotationBeanNameGenerator`（组件扫描与 `@MapperScan` 都挂）兜底，
`@MapperScan` 单基包 `com.betterschool.infrastructure.dao`。下文 §1 起的分层约定不变，仍按「域」叙述。

## 1. Maven 模块划分与依赖方向（严格单向）

```
app → trigger → case → domain → types
app → trigger → api, types, domain
app → infrastructure → domain
domain → types
types →（无内部依赖）
```

| 模块                 | 职责                                                         |
| -------------------- | ------------------------------------------------------------ |
| `xxx-api`            | 定义 api 接口 + DTO + Response，不依赖任何业务模块，后续dubbo对外暴露的就是这个 |
| `xxx-app`            | 应用启动层：`Application` 启动类 + `config` Bean 装配；MyBatis mapper XML 唯一存放处 |
| `xxx-domain`         | 领域层：业务领域核心，只依赖 types；**只注入本域仓储**，不注入他域 service/repository/port、不注入 `TransactionTemplate`；跨域读写一律由 case 层编排 |
| `xxx-case`           | 用例编排层：跨域组合顺序调用各域服务（无跨域事务）；只做编排不写领域业务 |
| `xxx-infrastructure` | 基础设施层：DAO / 缓存 / 网关 / 事件，实现 domain 定义的接口；完整技术组件（如 `ws/` WS 连接）也整体落在此层 |
| `xxx-trigger`        | 触发层：http / job / listener / ws，依赖 case / api / domain（WS 连接端额外依赖 infrastructure 的连接契约） |
| `xxx-types`          | 通用类型：常量 / 枚举 / 异常                                 |

约束：

- domain 的 `adapter` 层只保留 `repository`（一域一仓储）；`adapter/port` 已整体退役。
- 各模块间只允许上层依赖下层，禁止反向依赖或平级相互依赖。
- **domain 服务禁止互相注入（领域嵌套），且禁止用 port 读他域**：跨域读写（含跨域只读）的编排一律放 case 层（顺序调用，无跨域事务）。需要他域数据时，由 case 直接注入他域 service 取回，再把结果 / 判据所需的值传给本域；domain 层**只注入本域仓储**，不认识他域实体、枚举、service 或 port，不注入 `TransactionTemplate`。域方法返回"外部动作需求"结果对象（如 ProductPayResult）交 case 继续编排。
- **跨服务同一条规矩**：Dubbo 读外部服务（单体的用户中心等）也不在 domain 声明 port；由 case 编排时经他域 service 取回，或把技术适配留在 infrastructure、由 trigger 注入。**`domain` 下只有业务域，没有 `common` 段**——「很多域都要」不是建一个共享域的理由；`marketing.{layer}.common.*` 只放非域公共件（框架装配 / 统一响应 / JWT / outbox / wire 契约）——单体收口后该口径由「各层 `common` 段只放非域公共件、不放域模型与端口」继续沿用。同理，**配置项跟表走**：某张配置表配的是谁，就归谁的域（有效期归各自的桶域、返利规则归返利域），不设「配置域」。
- **port 已全部退役；技术能力一律走本域仓储**：业务用 WS 推送/Redis/MQ(outbox) 等技术设施，都做成自己域仓储上的方法（如 chat 域 `IChatRepository.pushMessage/pushRead/pushRecall`、notification 域 `pushNotif`），仓储实现在 infrastructure 里委托技术组件——不得为技术能力开 port。
- **WS 是独立技术组件，整体落 `infrastructure/ws/`（同 MQ 待遇）**：连接机器（注册表/心跳/互踢/多实例路由/实例订阅）与组件 API（`IWsConnectionHub` 连接登记+投递、`IWsTicketStore` 票据）与协议信封（`WsFrame/WsFrameTypeEnum`，组装收口在 `WsFrameFactory`）同住 infrastructure/ws——不进 domain（非业务域）、不进 types（types 只放常量/枚举/异常）、不散在 adapter/port 下。业务侧推送：各域仓储声明 `pushXxx(userId, 本域推送事件)`（只传事件对象，不碰帧信封），仓储实现经 `WsFrameFactory` 组帧后委托 `IWsConnectionHub`；chat/notification 的推送用例均经本域仓储投递，不得各自再声明第二套推送出口；一个用户一条全局连接，多业务按帧类型（MSG/READ/RECALL/NOTIF…）复用。trigger 连接端（ws/）注入组件 API（bs-trigger → bs-infrastructure 依赖边；bs-case 不依赖 infra）。domain 只放业务域——技术能力不占 domain 段。**无业务归属的技术能力直接落技术包、由 trigger 注入**（先例 `infrastructure/file/FileStorage`：图片上传，校验内聚在组件里，不设 case 编排、不开 domain port——原先的 `domain.file` 端口已按此条删除；`infrastructure/search`：倒排索引组件，`domain.search` 已按此条整域退役；`infrastructure/timeout`：超时任务组件——分表存储、扫描、重试退避、死信管理面与两个 hint 分片算法全住一个包，原「`domain.timeout` 技术域」历史遗留已按此条整体并入）。
- **一域一仓储**：每个业务域**恰有一个** `I<域>Repository` 接口与一个实现（读写同一接口）。实现内可注入多个 DAO（`I<域>Dao` 等）；PO、mapper XML、DAO 不因合并而变。
- **事务落位在仓储内**：需要事务时，域服务构造参数/实体后**只调一次**仓储方法；由**仓储方法自身**在实现里用编程式事务（`TransactionTemplate`，传播恒 `REQUIRED`）把一次操作的多次读改写收成一个原子用例，并在调用方既有事务中合流。**域服务不注入 `TransactionTemplate`**，也不自持事务边界。仓储方法的事务必须是 REQUIRED——取号序/原子性/与域服务事务合流都依赖它。
- **跨域一致性 = 幂等 + 重试，禁止 case 层包多域事务**：每个域方法自己域内事务原子；重试安全靠"状态机条件 UPDATE / 流水唯一键（wallet_detail uk_order_type、user_credit_detail uk_user_reason_ref）/ 事件流水判定（product_order_event 同订单 event_seq 顺序，如取消重试 = 最新事件已是本人取消即幂等返回）"；资金/信用分操作一律**流水先行插入**（INSERT IGNORE，冲突 = 已处理过幂等跳过），流水表是事实源。失败恢复：MQ 路径靠 broker 自动重试，HTTP 路径返回失败让客户端重试。
- **事务边界 = 仓储（ADR-0016，取代 ADR-0015 的组合事务端口）**：一个物理事务只允许写一个仓储（一个域）的表，**跨仓储禁止合并进同一事务**——组合事务端口模式废止（idle 侧 `IProductPayCompositeTxPort`/`ProductPayCompositeTxPort` 与 `CompositeTxExecutor` 组件、marketing 侧 `IPointsDeductPort`/`PointsDeductPort` 已全部删除，票 B/C 清退完毕，不再新增使用）。「状态翻转 + 资金动作」的资金链路一律**两段式**：资金段（流水先行 + 唯一键幂等的条件扣减/解冻/入账）与翻转段（条件 UPDATE 守卫前态）各自仓储本地事务，跨域编排顺序调用落 case 层；段落顺序保证宕机窗口只留**长款**中间态（资金悬置可凭流水回补，不允许短款）；「资金流水已落、状态翻转未命中」的孤儿由扫描 job **无状态窗口重扫**收敛（`productFundOrphanScan`，cron 0/30：终态订单补解冻/入账，动作全部流水唯一键幂等吸收重复，无 per-row 状态与死信），扫描判据是流水与业务行状态比对、零新增状态表。
- **超时注册的例外（超时内嵌化票 03）**：超时组件是技术组件（`infrastructure/timeout`，非业务域），业务仓储可在**自己已经开着事务的仓储方法**里调用同层技术组件 `TimeoutTaskStore.register(...)`，把超时行与业务行收进同一个本地事务（infra → infra 同层直用，不构成「域注入他域仓储」）。域服务不注入超时组件，也不自持事务边界。理由与迁移边界见 [`docs/architecture/timeout.md`](docs/architecture/timeout.md) §1/§3。

## 2. domain 层包结构（核心）

每个业务域一个顶层包（如 `domain.activity`、`domain.idle`、`domain.tag`），域内结构固定：

```

cn.xxx.domain.<业务域>/
├── model/
│   ├── entity/      实体对象  XxxEntity
│   └── valobj/      值对象    XxxVO（含 XxxEnumVO 枚举）
├── service/
│   └── <功能子包> IXxxService、IXxxServiceImpl、子包里也可以有其他包，用来服务IxxxService的实现
│   └── <功能子包>
└── adapter/
    └── repository/  仓储接口  I<域>Repository（一域恰一个，读写同接口）

```

**一域一仓储 / 事务落位**：`adapter/repository/` 下每个业务域**恰有一个** `I<域>Repository`（读写同接口，不再按聚合拆多个仓储）；需要事务的操作由仓储方法自身用编程式事务（`TransactionTemplate`，传播恒 `REQUIRED`）收成一个原子用例，域服务只调一次、**不注入 `TransactionTemplate`**（完整约束见 §1）。

## 3. infrastructure 层包结构（单体收口终态，票 06）

```
cn.xxx.infrastructure/
├── adapter/                     实现层（只有 repository 一棵树）
│   └── repository/
│       └── <域>/                实现 domain 仓储接口：XxxRepository implements IxxxRepository
│           （repository 根包放横跨多域、无归属域的写入契约/组件，先例 CountActionEnum /
│             CountEvent / LikeStateEnum——三个仓储的计数写入共用，但不属于合并引擎）
├── cache/                       缓存（域子包，如 post/）
├── dao/                         MyBatis Mapper 接口：IxxxDao（@Mapper），按域分包；
│                                无业务域的表（mq_outbox）Mapper 直接放 dao 根包
│   ├── <域>/po/                 持久化对象 PO（与表对应），base/Page 分页对象
│   └── po/                      无业务域表的 PO（如 MqOutbox）
├── merge/                       通用攒批引擎（键对齐 application.yml merge.batch.*）：
│                                只有 MergeConfig / MergeEngine / MergeGroup / MergeItem /
│                                MergeResult / MergeStrategy 六件，不认识任何业务表。谁用谁
│                                new MergeEngine(config, strategy)，并在自己的 @PreDestroy 里
│                                close()。奖品库存（AwardInventoryMergeWriter）与计数（三个仓储各自
│                                一个实例）都用它，没有专用引擎子包。发车口径：队列非空即发
│                                （无收集窗口，maxBatch 只是单批上界）
├── mergevirtual/                专属虚拟线程 leader 模型引擎（同语义并存候选，调用方暂不切换；
│                                复用 merge 包公开类型 MergeConfig/MergeStrategy/MergeResult，
│                                见 backend/docs/architecture/merge-virtual-leader-engine-spec.md；
│                                发车口径与 merge 相同：队列非空即发）
├── file/                        对象存储（MinIO）技术组件 FileStorage：白名单校验 + 上传，
│                                返回公开读 URL；trigger 直接注入（技术能力不开 domain port，
│                                同 ws 待遇），仓储要存文件也从本包取用
├── search/                      倒排索引技术组件 ISearchIndex（Elasticsearch）：只认索引名 / 检索字段 /
│                                文档对象，不认识业务实体与状态枚举；各域仓储直接注入，自传索引名与
│                                状态白名单（判据留域），索引只存可搜字段、展示字段回库反查
├── timeout/                     超时任务技术组件（原 `domain.timeout`「技术域」历史遗留已整体并入）：
│                                TimeoutTaskStore（16 分表唯一读写口，按键/槽位塞 hint）、TimeoutScanner
│                                （槽位认领 + 有界并行 + 循环拉批到空）、TimeoutSettler（结算分桶 + 指数退避）、
│                                TimeoutDeadLetterAdmin（死信管理面）、TimeoutTaskShardSlots（槽位单点函数）
│                                与库/表维两个 hint 算法；到点动作 SPI = ITimeoutHandler，
│                                装配落 trigger/job/TimeoutActionRouter（infra 看不见 case、case 看不见 infra，
│                                trigger 两边都看得见）；注册边 = 业务仓储在自己事务里直调 TimeoutTaskStore.register
│                                （业务行与超时行同生同死）；DAO/PO 仍按惯例住 dao/timeout
├── security/  ws/               其余技术包（ws = WS 连接机器 + IWsConnectionHub，见 §1）
└── gateway/                     调用外部 http/rpc 的接口定义（按需建，当前工程没有；gateway/dto 放外部 DTO）
```

规则：

- **技术包按需建，不建空包**；`adapter/repository` 与技术包平级。
- **依赖方向单向：adapter → 技术包**。repository 实现想用什么就从 dao / merge / cache /
  ws 等平级技术包取用；**技术包禁止 import `adapter.repository`**。横跨多域、无归属域的
  写入契约落 `adapter/repository` 根包（先例 `CountActionEnum` / `CountEvent` / `LikeStateEnum`：
  三个仓储共用，供各自 `MergeEngine` 的件与结果使用）。
- gateway 只定义调用外部的接口，由 adapter 层调用，通常用不到。
- repository 的类名与 domain 接口一一对应（去掉 `I`）；实现按域分包。
- **一域一仓储**：每个业务域恰一个 `XxxRepository implements I<域>Repository`；实现类内可注入**多个 DAO**
  （以及 RocketMQTemplate / Redisson 等），PO、mapper XML、DAO 不因合并仓储而变。
- **事务在仓储实现内**：多 DAO 的读改写用编程式事务（`TransactionTemplate`，传播恒 `REQUIRED`）收成一个原子用例；域服务不注入 `TransactionTemplate`。事务 Bean 每工程一个（`bs-app/.../config/TransactionConfig.java`），仅为仓储提供模板。

## 4. trigger 层包结构

```
cn.xxx.trigger/
├── http/       HTTP 接口服务：XxxController，Controller要实现api里的IXxxService
├── job/        定时任务：XxxJob（@Scheduled）
├── listener/   MQ 监听：XxxConsumer
├── ws/         长连接端：XxxHandler / XxxInterceptor / 端点常量（全站共享，WS 票起）
└── rpc/        Dubbo Provider：XxxDubboService（@DubboService，实现 -api 里的接口；营销票 01 起）
```

Dubbo 对称的两侧：Provider 在 `trigger/rpc/`；Consumer 由 case 层编排时取数（跨域服务经
他域 service，或由 trigger 注入 infrastructure 的技术适配），不再落 `domain/adapter/port`。
trigger 层依赖 case 层，按需注入 IXxxCase，通过 case 层与 domain 层交互；

## 5. case层包结构

```
cn.xxx.case/
├── IXxxCase       接口定义
└── XxxCaseImpl    实现类（实现类里Resouce按需注入domain的service接口，也就是对多个domain的编排）          
```

约束：**case 只注入 domain 的 service 接口，禁止注入任何 `I<域>Repository`（含 `@Resource` / 构造器 / 方法参数）**。
仓储只对 domain 服务开放；case 需要新的读写能力时，先在对应域服务上开方法，再由 case 调用。
`TransactionTemplate` 同样不进 case（事务边界在仓储实现内）。

## 6. api 层包结构

```
cn.xxx.api/
├── IxxxService    接口定义（如 IXxxService）
├── dto/           XxxRequestDTO / XxxResponseDTO，前端或dubbo的数据传输对象
└── response/      Response<T> {code, info, data}
```

## 7. app 层包结构

```
cn.xxx/             Application 必须放在根包，才能扫描到其他 module
├── Application.java
└── config/          Bean 装配（线程池/Redis/MQ/xxljob等配置类）
```

## 8. types

exception包

```java
@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AppException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private String code;
    private String info;

}
```
enums包

```java
@AllArgsConstructor
@Getter
public enum ResponseCodeEnum {

    SUCCESS("0000", "调用成功"),
    UN_ERROR("0001", "调用失败"),
    ;

    private String code;
    private String info;
}
```

common包,里面定义常量，例如redis key， mq之类

```java'
public class Constants {

    public final static String

}
```

## 9.硬性约定

1. **类名后缀决定分层归属**：Entity / VO / Aggregate / Repository / Dao / PO / Controller / Job / Consumer。
2. **接口 `I` 前缀、实现去掉 `I`**：domain 定义 `IxxxRepository`，infrastructure 实现 `XxxRepository`。
3. MyBatis mapper XML 统一放 `app/src/main/resources/mybatis/mapper/`，接口在 infrastructure 的 `dao` 包。
4. 新加业务域时，在 domain 下建 `model/service/adapter` 三件套（`adapter` 只放 `repository`），infrastructure 建对应 `adapter/repository` 与 `dao/<域>` 实现；不建 `adapter/port`。
5. 变量命名见名知意，如UserAccount userAccount，不要省略user；各种类的创建要符合DDD规范
6. **业务 ID 口径（uuid-biz-id 起）**：所有业务对象的对外唯一标识一律是 **UUID 字符串**（36 位连字符规范形，DB 列 `VARCHAR(36)`，Java 实体/PO/DTO/mapper 全链路 `String`）；由统一 `IIdGenerator`（types 模块，默认实现 `UuidIdGenerator` = `UUID.randomUUID()`）在 **insert 前生成、随行一次写入**。物理主键 `id BIGINT AUTO_INCREMENT` 保留为纯内部键：永不对外、永不再与业务键同值、禁止「业务列插 0 同事务回写物理 id」的旧形态（`bindBizId`/`useGeneratedKeys` 回写已全仓退役）。新增表/接口禁止用 `Long`/`bigint` 承载业务 ID；排序同值收敛用「时间 + 业务 ID」复合键，不要拿 UUID 当时间代理；分片/合并路由的哈希输入是业务 ID 字符串本身（`String.hashCode` 折叠），不做数值化假设。

7. **SQL 层零 JOIN**：mapper XML 全部语句只做单表读/写；跨表展示数据（昵称头像、计数窄表、关系列表的业务行、台账面额等）由仓储层「主查 + 按 id 集批量回查 + Java 拼装」多查收口，可见性谓词与聚合排序也一并挪到仓储 Java 侧。禁止再加 JOIN/UPDATE 多表关联语句（feed 索引重建等一步投影 SQL 同样拆成多查拼装）。豁免：只读 `NOT EXISTS` 守卫（防重放 / 孤儿判定类半连接，用于幂等 upsert 守卫与对账反查）允许跨表谓词——先例 `ProductViewCountMapper`、`AwardInventoryDetailMapper`。
