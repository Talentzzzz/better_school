# 01 包名/类名冲突扫描

Type: research
Status: resolved
Blocked by: —

## Question

marketing 七个模块的代码并入 `com.betterschool` 且领域包**平级铺开**（无 marketing 命名空间层）后，会发生哪些包名/类名冲突？扫描并输出：

1. 两侧 domain/case/infrastructure/trigger/types/api 全部类的对比：全限定名冲突（目标位置同包同名类）与简单名冲突（不同包同名类，合并后 import 撞车）清单；
2. marketing 模块 pom 依赖图（marketing-* 之间谁依赖谁），确认按层并入 `bs-*` 时的依赖方向合法性；marketing-* 是否反向依赖 bs-*；
3. domain 层 `domain/<域>/adapter/{port, repository}` 与 infra 层 `adapter/{port, repository}` 同名双层的现状清单（确认 domain 层不动的前提下 infra 重组无歧义）；
4. marketing 侧 infra 的按域子树（inventory/points/raffle/rebate/{adapter,dao}）与 common/{dao,outbox,security} 的完整类清单，作为 06 票压平并入的输入；
5. 每类冲突的重命名/处置建议。

发现全文写入 `research/01-name-collision.md`，本票 Resolution 只留指针与要点。

## Answer

完整报告：[research/01-name-collision.md](../research/01-name-collision.md)。要点：

1. **全限定名冲突 10 处**：marketing-app 6 个 config 类（MybatisConfig / RocketMQConsumerConfig / SecurityConfig / TransactionConfig / VirtualThreadRocketMQListenerContainerRegistrar / XxlJobConfig）平级化后与 bs-app `com.betterschool.config.*` 逐一撞名；2 个集成测试 probe 类撞 bs-app test；`types.common.Constants` 撞 bs-types；最隐蔽一处：rocketmq 同包补丁类 `org.apache.rocketmq.client.impl.consumer.VirtualThreadConsumeMessageConcurrentlyService` **两侧今天就完全同名**。
2. **跨服务简单名冲突 29 个类名**（净新增 19 处）：outbox 三件套、Jwt 双件、Merge 双套、Response/PageResult/ResponseCode/OutboxStatus 等 types/api 层重复拷贝；marketing 内部另有重名 3 组（Constants ×4、IUserQueryPort ×2、PingController ×2）。
3. **反向依赖 1 处**：`marketing-infrastructure → bs-api`（PointsUserQueryPort / RaffleUserQueryPort 引用 `IUserQueryService`、`UserBasicDTO`）；合并后需给 bs-infrastructure 补依赖或改调用路径。
4. **adapter 双层模式两侧都有，但包序相反**：bs 是 `infrastructure.adapter.X.<域>`，marketing 是 `infrastructure.<域>.adapter.X`，且 marketing port 多一层子包——06 票压平时按 bs 包序统一。
5. **marketing infra 按域清单**：inventory 15 类、points 11 类、raffle 29 类、rebate 3 类、common 7 类、merge 基建 6+3 类，均带 FQCN，可直接作 06 票压平输入。

另附非类名事实：启动类需收敛为一个；marketing-app 已用 FQCN bean 命名而 bs-app 未用；bs-case 里已存在 marketing 用例类（LotteryDrawnMessage / PointsChangedMessage）。
