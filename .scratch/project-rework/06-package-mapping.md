# 票 06 · 包名映射表（施工用，收口后并入 map.md）

旧工程 `backend/marketing-asset/`（包根 `com.betterschool.asset`）→ 新工程 `backend/marketing/`（包根 `com.betterschool.marketing`）。

## 0. 机械替换规则（先做替换，再处理落位差异）

1. 文件级：`com/betterschool/asset/` → `com/betterschool/marketing/`（目录随包名同迁）。
2. 包声明与 import：`com.betterschool.asset` → `com.betterschool.marketing`（**全文替换，包括字符串字面量与注释**）。
3. 落位差异：按下表把 `<layer>` 下的包路径改成 `<layer>.points` 或 `<layer>.common`。
4. `marketing-asset-app` → `marketing-app`、`marketing-asset-types` → `marketing-types`（模块目录名与 artifactId 全改）。
5. Maven 模块目录名：`marketing-asset-<x>` → `marketing-<x>`。

## 1. types / api / domain 落位

| 旧 FQCN | 新 FQCN |
| --- | --- |
| `...asset.types.common.Constants` | `...marketing.types.points.Constants` |
| `...asset.types.enums.OutboxStatus` | `...marketing.types.common.OutboxStatus` |
| `...asset.types.enums.PointsReasonType` | `...marketing.types.points.PointsReasonType` |
| `...asset.types.enums.ResponseCode` | `...marketing.types.common.ResponseCode` |
| `...asset.types.exception.AppException` | `...marketing.types.common.AppException` |
| `...asset.api.IPointsExchangeService` | `...marketing.api.points.IPointsExchangeService` |
| `...asset.api.IPointsService` | `...marketing.api.points.IPointsService` |
| `...asset.api.dto.PointsBalanceResponseDTO` | `...marketing.api.points.dto.PointsBalanceResponseDTO` |
| `...asset.api.dto.PointsDeductRequestDTO` | `...marketing.api.points.dto.PointsDeductRequestDTO` |
| `...asset.api.dto.PointsDeductResponseDTO` | `...marketing.api.points.dto.PointsDeductResponseDTO` |
| `...asset.api.dto.PointsTransactionResponseDTO` | `...marketing.api.points.dto.PointsTransactionResponseDTO` |
| `...asset.api.dto.UserBasicResponseDTO` | `...marketing.api.common.dto.UserBasicResponseDTO` |
| `...asset.api.response.PageResult` | `...marketing.api.common.response.PageResult` |
| `...asset.api.response.Response` | `...marketing.api.common.response.Response` |
| `...asset.domain.points.adapter.port.marketing.IUserQueryPort` | `...marketing.domain.common.adapter.port.marketing.IUserQueryPort` |
| `...asset.domain.points.adapter.repository.IPointsRepository` | `...marketing.domain.points.adapter.repository.IPointsRepository` |
| `...asset.domain.points.model.entity.PointsConsumeEntity` | `...marketing.domain.points.model.entity.PointsConsumeEntity` |
| `...asset.domain.points.model.entity.PointsLotEntity` | `...marketing.domain.points.model.entity.PointsLotEntity` |
| `...asset.domain.points.model.valobj.LotteryDrawnMessage` | `...marketing.types.common.LotteryDrawnMessage`（**跨域 wire 契约**） |
| `...asset.domain.points.model.valobj.PointsChangedMessage` | `...marketing.domain.points.model.valobj.PointsChangedMessage` |
| `...asset.domain.points.model.valobj.PointsDeductResultVO` | `...marketing.domain.points.model.valobj.PointsDeductResultVO` |
| `...asset.domain.points.model.valobj.PointsTransactionDirectionVO` | `...marketing.domain.points.model.valobj.PointsTransactionDirectionVO` |
| `...asset.domain.points.model.valobj.PointsTransactionVO` | `...marketing.domain.points.model.valobj.PointsTransactionVO` |
| `...asset.domain.points.model.valobj.marketing.UserBasicVO` | `...marketing.domain.common.model.valobj.marketing.UserBasicVO` |
| `...asset.domain.points.service.IPointsAccountService` | `...marketing.domain.points.service.IPointsAccountService` |
| `...asset.domain.points.service.PointsAccountServiceImpl` | `...marketing.domain.points.service.PointsAccountServiceImpl` |

## 2. infrastructure 落位

| 旧 FQCN | 新 FQCN |
| --- | --- |
| `...asset.infrastructure.adapter.port.marketing.UserQueryPort` | `...marketing.infrastructure.common.adapter.port.marketing.UserQueryPort` |
| `...asset.infrastructure.adapter.repository.points.PointsRepository` | `...marketing.infrastructure.points.adapter.repository.PointsRepository` |
| `...asset.infrastructure.dao.outbox.IOutboxDao` | `...marketing.infrastructure.common.dao.outbox.IOutboxDao` |
| `...asset.infrastructure.dao.outbox.po.OutboxPO` | `...marketing.infrastructure.common.dao.outbox.po.OutboxPO` |
| `...asset.infrastructure.dao.points.IPointsDao` | `...marketing.infrastructure.points.dao.IPointsDao` |
| `...asset.infrastructure.dao.points.po.PointsConsumePO` | `...marketing.infrastructure.points.dao.po.PointsConsumePO` |
| `...asset.infrastructure.dao.points.po.PointsLotPO` | `...marketing.infrastructure.points.dao.po.PointsLotPO` |
| `...asset.infrastructure.dao.points.po.PointsTransactionPO` | `...marketing.infrastructure.points.dao.po.PointsTransactionPO` |
| `...asset.infrastructure.mq.OutboxMessageSender` | `...marketing.infrastructure.common.outbox.OutboxMessageSender` |
| `...asset.infrastructure.outbox.OutboxMessageStore` | `...marketing.infrastructure.common.outbox.OutboxMessageStore` |
| `...asset.infrastructure.security.JwtAuthenticationFilter` | `...marketing.infrastructure.common.security.JwtAuthenticationFilter` |
| `...asset.infrastructure.security.JwtService` | `...marketing.infrastructure.common.security.JwtService` |

## 3. trigger / app 落位

| 旧 FQCN | 新 FQCN |
| --- | --- |
| `...asset.trigger.http.GlobalExceptionHandler` | `...marketing.trigger.common.http.GlobalExceptionHandler` |
| `...asset.trigger.http.PingController` | `...marketing.trigger.common.http.PingController` |
| `...asset.trigger.http.PointsController` | `...marketing.trigger.points.http.PointsController` |
| `...asset.trigger.http.security.SecurityUtils` | `...marketing.trigger.common.http.security.SecurityUtils` |
| `...asset.trigger.job.PointsExpireScanJob` | `...marketing.trigger.points.job.PointsExpireScanJob` |
| `...asset.trigger.listener.LotteryDrawnConsumer` | `...marketing.trigger.points.listener.LotteryDrawnConsumer` |
| `...asset.trigger.rpc.PointsExchangeDubboService` | `...marketing.trigger.points.rpc.PointsExchangeDubboService` |
| `org.apache.rocketmq.client.impl.consumer.VirtualThreadConsumeMessageConcurrentlyService` | 包名不变（框架补丁类，全路径照抄） |
| `...asset.config.MybatisConfig` | `...marketing.config.MybatisConfig` |
| `...asset.config.RocketMQConsumerConfig` | `...marketing.config.RocketMQConsumerConfig` |
| `...asset.config.SecurityConfig` | `...marketing.config.SecurityConfig` |
| `...asset.config.TransactionConfig` | `...marketing.config.TransactionConfig` |
| `...asset.config.VirtualThreadRocketMQListenerContainerRegistrar` | `...marketing.config.VirtualThreadRocketMQListenerContainerRegistrar` |
| `...asset.config.XxlJobConfig` | `...marketing.config.XxlJobConfig` |
| `...asset.MarketingAssetApplication` | `...marketing.MarketingApplication` |

## 4. 测试落位

| 旧 FQCN | 新 FQCN |
| --- | --- |
| `...asset.integration.ConsumeHandlerVirtualThreadIntegrationTest` | `...marketing.integration.common.ConsumeHandlerVirtualThreadIntegrationTest` |
| `...asset.integration.PointsDeductIntegrationTest` | `...marketing.integration.points.PointsDeductIntegrationTest` |
| `...asset.integration.PointsExpireIntegrationTest` | `...marketing.integration.points.PointsExpireIntegrationTest` |
| `...asset.integration.PointsLotteryWinIntegrationTest` | `...marketing.integration.points.PointsLotteryWinIntegrationTest` |
| `...asset.integration.WebHandlerVirtualThreadIntegrationTest` | `...marketing.integration.common.WebHandlerVirtualThreadIntegrationTest` |
| `...asset.integration.support.PointsApiTestBase` | `...marketing.integration.support.PointsApiTestBase` |
| `...asset.integration.support.VtConsumeThreadProbe` | `...marketing.integration.support.VtConsumeThreadProbe` |
| `...asset.integration.support.VtWebThreadProbe` | `...marketing.integration.support.VtWebThreadProbe` |

## 5. 资源文件 / 装配

| 旧 | 新 |
| --- | --- |
| `marketing-asset-app/resources/application.yml` | `marketing-app/resources/application.yml` |
| `spring.application.name: marketing-asset` | `marketing` |
| `dubbo.application.name: marketing-asset` | `marketing` |
| `rocketmq.producer.group: marketing-asset-producer` | `marketing-producer` |
| `dubbo.scan.base-packages: com.betterschool.asset` | `com.betterschool.marketing` |
| `xxl.job.executor.appname: marketing-asset-executor` | `marketing-executor` |
| `xxl.job.executor.log-path: ./logs/xxl-job-asset` | `./logs/xxl-job-marketing` |
| `logging.level.com.betterschool.asset*` | `logging.level.com.betterschool.marketing*` |
| `marketing-asset-app/resources/mybatis/mapper/{PointsMapper,OutboxMapper}.xml` | `marketing-app/resources/mybatis/mapper/*`（namespace 按映射表改） |
| `marketing-asset/sql/**` | `marketing/sql/**`（票本不动内容） |
| `marketing-asset/docs/**` | `marketing/docs/**`（正文引用随票 18 收口） |

## 6. 不变项（零行为变更）

- 表名、SQL、DDL、库名（`bs_asset` / `bs_asset_test`；库名收敛归票 08）。
- 消息体字段、topic 名（`bs-referral-grouped` / `bs-points-changed` / `bs-lottery-drawn`）、消费组名（`bs-points-lottery-drawn-group`）。
- `IPointsService` / `IPointsExchangeService` 对外签名。
- 端口：dev HTTP 8081 / tri 50052 / actuator 9998（**原 asset 即此值**，无需改动）；test actuator 9990 + tri 取空闲端口。
