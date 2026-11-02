

# 项目编码规范（DDD 分包分模块约定）

## 1. Maven 模块划分与依赖方向（严格单向）

`backend/` 下是两个互相独立的 Maven project（无 reactor）：单体 `better-school/`（bs-* 七模块）+ 营销 `marketing/`（`marketing-*` 七模块；票 06 并入原 `marketing-asset` 的积分域、票 07 并入原 `marketing-activity` 的抽奖域，两域同工程）。每个工程内部模块划分与依赖方向同下表：

**适用范围**：本文件管 `backend/` 下这两个工程。超时中心已从独立工程内嵌进单体（见 [`docs/architecture/timeout.md`](docs/architecture/timeout.md)），不再是独立 project。

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
| `xxx-domain`         | 领域层：业务领域核心，只依赖 types；**只注入本域仓储与本域 port**，不注入他域 service/repository、不注入 `TransactionTemplate`（跨域动作返回结果对象；跨域只读经本域 port） |
| `xxx-case`           | 用例编排层：跨域组合顺序调用各域服务（无跨域事务）；只做编排不写领域业务 |
| `xxx-infrastructure` | 基础设施层：DAO / 缓存 / 网关 / 事件，实现 domain 定义的接口 |
| `xxx-trigger`        | 触发层：http / job / listener，只依赖 case / api / domain |
| `xxx-types`          | 通用类型：常量 / 枚举 / 异常                                 |

约束：

- domain 的 `adapter` 层只定义接口，由 infrastructure 实现。
- 各模块间只允许上层依赖下层，禁止反向依赖或平级相互依赖。
- **domain 服务禁止互相注入（领域嵌套）**：跨域写组合的编排一律放 case 层（顺序调用，无跨域事务）；域方法返回"外部动作需求"结果对象（如 ErrandXxxResult）。**跨域只读一律经消费方域定义的 port**：需要读他域数据时，在**消费方域**的 `domain/<X>/adapter/port/…` 定义 port（判据留在消费方域，port 里不放判据逻辑），实现落 `infrastructure/adapter/port/<域>/`（实现内可只读他域仓储）；域层从此**只注入本域仓储 + 本域 port**，不再注入他域仓储接口、不再注入 `TransactionTemplate`。
- **一域一仓储**：每个业务域**恰有一个** `I<域>Repository` 接口与一个实现（读写同一接口）。实现内可注入多个 DAO（`I<域>Dao` 等）；PO、mapper XML、DAO 不因合并而变。
- **事务落位在仓储内**：需要事务时，域服务构造参数/实体后**只调一次**仓储方法；由**仓储方法自身**在实现里用编程式事务（`TransactionTemplate`，传播恒 `REQUIRED`）把一次操作的多次读改写收成一个原子用例，并在调用方既有事务中合流。**域服务不注入 `TransactionTemplate`**，也不自持事务边界。仓储方法的事务必须是 REQUIRED——取号序/原子性/与域服务事务合流都依赖它。
- **跨域一致性 = 幂等 + 重试，禁止 case 层包多域事务**：每个域方法自己域内事务原子；重试安全靠"状态机条件 UPDATE / 流水唯一键（wallet_detail uk_order_type、user_credit_detail uk_user_reason_ref）/ 幂等位（errand_order.taker_canceled）"；资金/信用分操作一律**流水先行插入**（INSERT IGNORE，冲突 = 已处理过幂等跳过），流水表是事实源。失败恢复：MQ 路径靠 broker 自动重试，HTTP 路径返回失败让客户端重试。
- **超时注册的显式例外（超时内嵌化票 03）**：trade / errand / review 三个业务仓储可在**自己已经开着事务的仓储方法**里注入并调用 `ITimeoutRepository.register(...)`，把超时行与业务行收进同一个本地事务。除此之外仍遵守「域不注入他域仓储」；域服务不注入超时仓储，也不自持事务边界。理由与迁移边界见 [`docs/architecture/timeout.md`](docs/architecture/timeout.md) §1/§3。

## 2. domain 层包结构（核心）

每个业务域一个顶层包（如 `domain.activity`、`domain.trade`、`domain.tag`），域内结构固定：

```

cn.xxx.domain.<业务域>/
├── model/
│   ├── aggregate/   聚合对象  XxxAggregate
│   ├── entity/      实体对象  XxxEntity
│   └── valobj/      值对象    XxxVO（含 XxxEnumVO 枚举）
├── service/
│   └── <功能子包> IXxxService、IXxxServiceImpl、子包里也可以有其他包，用来服务IxxxService的实现
│   └── <功能子包>
└── adapter/
    ├── repository/  仓储接口  I<域>Repository（一域恰一个，读写同接口）
    └── port/        外部端口  I<域>XxxPort（本域需要的外部能力：跨域只读 / 外部系统）

```

**一域一仓储 / 事务落位**：`adapter/repository/` 下每个业务域**恰有一个** `I<域>Repository`（读写同接口，不再按聚合拆多个仓储）；需要事务的操作由仓储方法自身用编程式事务（`TransactionTemplate`，传播恒 `REQUIRED`）收成一个原子用例，域服务只调一次、**不注入 `TransactionTemplate`**（完整约束见 §1）。

**当领域需要调用infra 对多个entity进行一次事务存储时，将多个entity封装为聚合对象；如果事务内需要的话，聚合对象内也可以有其他属性，来辅助事务**

## 3. infrastructure 层包结构

```
cn.xxx.infrastructure/
├── adapter/
│   ├── repository/  
		|_ 领域包
			|_ 实现 domain 仓储接口：XxxRepository implements IxxxRepository
		|_ 领域包
			|_ 实现 domain 仓储接口：XxxRepository implements IxxxRepository
│   └── port/        实现 domain 端口：   XxxPort implements IxxxPort,分包同上
├── dao/             MyBatis Mapper 接口：IxxxDao（@Mapper）
│   └── po/          持久化对象 PO（与表对应），base/Page 分页对象
├── gateway/         定义 http/rpc 调用外部接口；gateway/dto 放外部 DTO
```

规则：
- gateway 只定义调用外部的接口，由 adapter 层调用，通常用不到
- repository / port 的类名与 domain 接口一一对应（去掉 `I`）。
- **一域一仓储**：每个业务域恰一个 `XxxRepository implements I<域>Repository`；实现类内可注入**多个 DAO**（以及共享 outbox / Redisson 等），PO、mapper XML、DAO 不因合并仓储而变。
- **事务在仓储实现内**：多 DAO 的读改写用编程式事务（`TransactionTemplate`，传播恒 `REQUIRED`）收成一个原子用例；域服务不注入 `TransactionTemplate`。事务 Bean 每工程一个（`<工程>-app/.../config/TransactionConfig.java`），仅为仓储提供模板。

## 4. trigger 层包结构

```
cn.xxx.trigger/
├── http/       HTTP 接口服务：XxxController，Controller要实现api里的IXxxService
├── job/        定时任务：XxxJob（@Scheduled）
├── listener/   MQ 监听：XxxConsumer
├── ws/         长连接端：XxxHandler / XxxInterceptor / 注册表 / 推送服务（chat 票 04 起）
└── rpc/        Dubbo Provider：XxxDubboService（@DubboService，实现 -api 里的接口；营销票 01 起）
```

Dubbo 对称的两侧：Provider 在 `trigger/rpc/`，Consumer 的 `@DubboReference` 落在
`infrastructure/adapter/port/<域>/`（实现 `domain/adapter/port/<域>/IXxxPort`），
**域层与 case 层不认识 Dubbo 注解与对端的 api DTO**，翻译在 infrastructure 完成。
例外（端口实现落 case 层）：超时域的回调端口 `ITimeoutTaskCallbackPort` 由
`usecase.timeout.TimeoutTaskCallbackDispatcher` 实现——分发要按 `taskType` 调各域 case，而 infrastructure
看不到 case 层。端口接口仍定义在 `domain.timeout/adapter/port`，实现落点在 case 层是**显式例外**，
不要把 case 层依赖反向灌进 infrastructure 来「修正」它。

## 5. api 层包结构

```
cn.xxx.api/
├── IxxxService    接口定义（如 IXxxService）
├── dto/           XxxRequestDTO / XxxResponseDTO，前端或dubbo的数据传输对象
└── response/      Response<T> {code, info, data}
```

## 6. app 层包结构

```
cn.xxx/             Application 必须放在根包，才能扫描到其他 module
├── Application.java
└── config/          Bean 装配（线程池/Redis/MQ/xxljob等配置类）
```

## 7. types

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
public enum ResponseCode {

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

**多域工程的类型落位（票 06 起，营销 `marketing/` 为范例）**：一个工程内有两个以上业务域时，
`types`（以及 api / domain / infrastructure / trigger 各层）**先按域分段、再按上面三类分**：

- 域专属（如 `Constants`、`PointsReasonType`）→ `types.<域>.*`（`marketing.types.points`）。
  域自有的常量/枚举放 `common` 会在第二域并入时撞名（lottery 侧各有自己的 `Constants`/枚举）。
- 跨域公共（框架装配、统一响应、JWT/安全、MyBatis/事务、MQ 容器与虚拟线程、探针）→
  `types.common` / `<layer>.common.*`，**全工程只留一份**。
- `AppException` / `ResponseCode` / `OutboxStatus` → `types.common`；跨域 wire 契约（如 `LotteryDrawnMessage`）→ `types.common`。
- 同名类多时，启动类与 `@MapperScan` 都挂 `FqcnAnnotationBeanNameGenerator`（bean 名改用 FQCN），
  且 `@MapperScan` **逐域列出**，不能扫它们的伪公共前缀。
  先例：`marketing/`（票 06 起 points，票 07 加 lottery，两域各占一个域段）。

单域工程不必强行分域段；本仓两个工程都是多域工程（单体按业务域、营销按 points / drawcount / config / raffle / inventory）。

## 8. 硬性约定

1. **类名后缀决定分层归属**：Entity / VO / Aggregate / Repository / Port / Dao / PO / Controller / Job / Consumer。
2. **接口 `I` 前缀、实现去掉 `I`**：domain 定义 `IxxxRepository`，infrastructure 实现 `XxxRepository`。
3. MyBatis mapper XML 统一放 `app/src/main/resources/mybatis/mapper/`，接口在 infrastructure 的 `dao` 包。
4. 新加业务域时，在 domain 下建 `model/service/adapter` 三件套，infrastructure 建对应 `adapter/dao` 实现。
5. 变量命名见名知意，如UserAccount userAccount，不要省略user；各种类的创建要符合DDD规范
6. **业务 ID 口径（uuid-biz-id 起）**：所有业务对象的对外唯一标识一律是 **UUID 字符串**（36 位连字符规范形，DB 列 `VARCHAR(36)`，Java 实体/PO/DTO/mapper 全链路 `String`）；由统一 `IIdGenerator`（types 模块，默认实现 `UuidIdGenerator` = `UUID.randomUUID()`）在 **insert 前生成、随行一次写入**。物理主键 `id BIGINT AUTO_INCREMENT` 保留为纯内部键：永不对外、永不再与业务键同值、禁止「业务列插 0 同事务回写物理 id」的旧形态（`bindBizId`/`useGeneratedKeys` 回写已全仓退役）。新增表/接口禁止用 `Long`/`bigint` 承载业务 ID；排序同值收敛用「时间 + 业务 ID」复合键，不要拿 UUID 当时间代理；分片/合并路由的哈希输入是业务 ID 字符串本身（`String.hashCode` 折叠），不做数值化假设。

## 9. 维护后端文档

```
后端文件夹/
├─ docs/
│  ├─ README.md        ← 项目一句话说明 + 文档索引
│  ├─ db/
│  │  ├─ README.md     ← 库总览：有哪些表、归属哪个业务
│  │  ├─ user.md       ← 用户相关表
│  │  ├─ order.md      ← 订单相关表
│  │  └─ ...
│  ├─ api/
│  │  ├─ README.md     ← 接口总览：模块划分
│  │  ├─ auth.md       ← 登录注册
│  │  ├─ order.md      ← 订单接口
│  │  └─ ...

```

工作时维护这些文档

### 数据库文档

把表结构导出后，按业务拆成多个 md，放进 docs/db/。不用长篇大论

| 必写                                         | 建议写                                      |
| -------------------------------------------- | ------------------------------------------- |
| 表名、字段名、字段类型、主键/唯一/非空等约束 | 默认值、枚举含义、外键/关联、业务注意点     |
| **可补充**                                   | **别写成**                                  |
| 索引、软删规则、谁在写这张表、禁止怎么用     | 大段废话、复制整份 ORM 源码、和代码重复三遍 |

------

```
# 用户表 user

> 业务：账号与登录；归属模块：auth

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK|主键|
|mobile|varchar(20)|UNIQUE, NOT NULL|手机号|
|password_hash|varchar(100)|NOT NULL|密码哈希，禁止明文|
|status|tinyint|NOT NULL, DEFAULT 1|1正常 0禁用|
|created_at|datetime|NOT NULL|创建时间|
|updated_at|datetime|NOT NULL|更新时间|

## 关联
- 1:N → user_profile（资料）
- 被 order.user_id 引用

## 注意
- 查用户请走 mobile / id，不要扫全表
- 软删除字段若后续加，统一用 deleted_at
```

### 接口文档
维护一套「像代码一样短」的 Markdown
> 每个接口写清：路径、方法、鉴权、关键参数、成功/失败要点。不求漂亮，要求 AI 丢进对话里立刻能对上号。
```
# 订单模块接口

## POST /api/orders
创建订单

- Auth: 需要登录
- Body:
  - skuId: string 必填
  - count: number 必填，>=1
  - addressId: string 必填
- 成功: { orderId, status }
- 失败常见: 库存不足 / 地址无效
- 流程图（大体流程：例如一次请求到Controller，再到domain，再到infra，经过了哪些关键步骤，比如mysql redis等等）
```

### 什么时候写

规范不是写一次就完。关键是：**AI 每动一次代码，文档就跟一次。**

改了哪些表 / 字段？同步进 `docs/db/`

新增或变更了哪些接口？同步进 `docs/api/`

## 10. 集成测试环境

集成测试连的是**预先起好的常驻栈，不是自建容器**——测试进程不创建、不销毁任何容器。

**跑测试前必须先起环境**（幂等，可重复执行）。正式入口是 `backend/scripts/dev-stack.ps1`（票 17 引入，Windows PowerShell 5.1；`docker compose up -d` 的容器里本就含测试侧的 `bs-nacos-test` / `bs-rocketmq-namesrv-test`，中间件一套命令全覆盖）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/dev-stack.ps1 up
```

`scripts/test-env.sh` 仍是 bash 环境下的参考口径（`up` / `reset-test-db`），但这台机器的 bash/WSL 是坏的，跑不了它——别在 Windows 上试。注意 `dev-stack.ps1 db-reset` 只重建两个**开发**库；重建 `*_test` 库目前没有 PowerShell 等价命令，需要手工 DROP 后重灌 `schema.sql`。

环境没起时测试会直接连接失败——这是刻意的，不要加自动兜底。

**隔离口径**（开发与测试共用 MySQL / Redis / MinIO 容器，靠各自的多租户机制隔离）：

| 项 | 开发 | 测试 |
| --- | --- | --- |
| MySQL 库 | `better_school` / `bs_marketing` | `better_school_test` / `bs_marketing_test` |
| Redis 逻辑库 | `database: 0` | `database: 1` |
| MinIO 桶 | `bs-files` | `bs-files-it` |
| Nacos | 8848 | 18848（独立容器） |
| RocketMQ namesrv | 9876 | 19876（独立容器） |

测试侧地址的唯一事实来源是 `better-school/bs-app/src/test/resources/application-test.yml`，不要在测试代码里另写地址。例外（设计如此，见 yml 头注释）：Nacos 地址与 Dubbo 端口必须早于 config-data 阶段，由 `CommunityApiTestBase` 以 `System.setProperty` 注入。营销服务的测试侧事实来源同理在 `marketing/marketing-app/src/test/resources/application-test.yml`（三个域的测试基类同样经 `System.setProperty` 注入 Nacos/Dubbo）。

**写法**：

- 新增集成测试：继承既有基类（如 `CommunityApiTestBase`），**不自行创建容器**、不写死连接地址
- 不写增量迁移脚本（票 05 起）：`schema.sql` 是唯一 DDL 来源，改表 = 改 schema.sql + 重建库；`sql/migrations/` 与迁移测试基类已删除

**改动 schema 后必须重建测试库**——常驻容器不会自动跟上 `schema.sql` 的变化，否则测试跑在过期 schema 上出假绿：

```bash
scripts/test-env.sh reset-test-db        # bash 环境（本机不可用）
# Windows 本机手工等价：
#   docker exec bs-mysql mysql -uroot -p<pwd> -e "DROP DATABASE IF EXISTS xxx_test; CREATE DATABASE xxx_test;"
#   再把对应工程的 schema.sql 灌进去（中文 SQL 走 cmd /c type | docker exec -i … --default-character-set=utf8mb4）
```

