# map：本地测试基础设施（容器常驻 + 开发/测试共用一套栈）落地

## Destination

按 [spec](spec.md)（唯一需求来源）完成落地：测试侧**彻底移除 Testcontainers**，一套常驻容器同时服务开发与测试（MySQL / Redis / MinIO 共用，Nacos / RocketMQ 独立容器），12 个迁移测试共用一个 MySQL、每用例独立 database。验收 = 323 条既有用例全绿且断言一字不改 + 全量 828s → ~600s + 日志无 `Creating container for image`。

## 依赖图（Blocked by）

```
01 环境准备（compose 测试栈 + init.sql 拆段） ─┬─→ 02 迁移测试脱离容器 ─┐
                                              └─→ 03 API 测试脱离容器 ─┴─→ 04 摘依赖 + 文档收口
```

- 02 与 03 **可并行**：两者都只依赖 01，互不接触对方的文件（`integration/sql/*` vs `support/CommunityApiTestBase` + `application-test.yml`）。
- 04 必须等两者都完成——依赖声明还在时摘掉 Testcontainers 会直接编译/运行断掉。

## Notes

- **事实校核（2026-09-15，Explore 实测）**：44 个测试类 `extends CommunityApiTestBase`（改基类一处即覆盖全部）；12 个迁移测试各持一个 `@Container MySQLContainer`（旧 schema 常量 + 存量数据插入 + 迁移后断言自留）；全仓只有 1 处 `@DynamicPropertySource`，无第二处 Spring 上下文。
- **Redisson 由 `redisson-spring-boot-starter` 读 `spring.data.redis.*`** ⇒ `application-test.yml` 里 `database: 1` 一处即同时隔离 Lettuce 与 Redisson（基类 `registerAndLogin` 读验证码的路径自动落测试逻辑库）。
- **`spring.cloud.nacos.server-addr` 搬不进 yml**：`spring.config.import` 在 config-data 阶段解析，早于 profile 文档与 `@DynamicPropertySource`。基类 `System.setProperty` 必须留着，只是值由容器映射变成固定 `127.0.0.1:18848`；固定端口后 gRPC offset 那行可删（默认 offset 即 1000）。
- **共用容器要挂两套 host 端口**（`13306/16379/19000-19001` 与 dev 端口并存）——由 spec 的端口表推导出的工作项，落在票 01。
- **MinIO 凭据随共用而改变**：dev 是 `bsadmin/bs123456`，测试现状是 `bs123456-test`（测试专用容器时代）⇒ 票 03 必须同步，只有桶名 `bs-files-it` 保留差异。
- **拆段会波及既有测试**：`CommunityApiTestBase` 把 `sql/init.sql` bind 进 MySQL 容器，文件改名后该路径失效 ⇒ 票 01 带一行过渡改动，票 03 再连方法一起删掉；两票之间既有 323 用例保持全绿。**实际落法不是票面写的「指向 `init-dev.sql`」**：DDL 全在 `schema.sql`，`init-dev.sql` 只剩建库 + `USE`，而 entrypoint 的每个 init 文件各起一条 mysql 连接（`USE` 不跨连接）⇒ bind `schema.sql` + 给容器 `MYSQL_DATABASE=better_school`（`withDatabaseName`）才对。票 03 照这个形状删。
- **票 01 之后的残留表述**（都不影响运行，随归属的票收口）：`backend/docs/testing.md` 的「建库走 sql/init.sql」与 `PostReviewFirstReviewIntegrationTest` 里一行注释归票 04；12 个迁移测试 javadoc 的「对齐 init.sql 在 NNN 已应用」归票 02（该票本就要重写这 12 个子类）。
- 领域词汇遵循仓库根 `CONTEXT.md`；问题一律查 spec 对应章节，本 effort 不重开设计讨论。

## Decisions so far

<!-- 索引：每个已关闭票一行；细节在该票 ## Answer 里 -->

- 2026-09-15 拆票定稿（用户拍板三处）：**粒度 4 张**（01 环境 / 02 迁移测试 / 03 API 测试 / 04 依赖与文档）；**起环境加一个后端脚本**（story 15/16 落到 `up` / `reset-test-db` 两个子命令，而非只写文档命令）；**迁移测试临时库命名 = 固定前缀 + 类名**（如 `bs_it_remove_anonymous`，可在 `docker exec` 里直接看到，且 `DROP` 语句禁止出现 `better_school` 字面量）。
- 2026-09-15 票 01 resolved：环境准备落地（`scripts/test-env.sh` 形状、测试栈容器名 `bs-nacos-test` / `bs-rocketmq-namesrv-test`、拆段与过渡性改造的真实形状）。两处偏离票面的地方（过渡改动 bind `schema.sql` + `withDatabaseName` 而非 `init-dev.sql`；init 文件的「引入 schema」靠 `cat` 拼接而非 `SOURCE`）见票 01 Answer。**票 02 与票 03 已解锁，可并行**。
- 2026-09-15 spec 内部冲突的收口口径（进票 03）：「基类改造」节说去掉**整个** `@DynamicPropertySource`，「测试进程的 host 端口」表说 `dubbo.protocol.port` **保持随机取、保留**。取两者都成立的做法——空闲端口与 nacos 地址同路写进基类静态块的 `System.setProperty`，基类不再有属性源。
- 2026-09-15 票 04 resolved：Testcontainers 依赖 / 根 pom 属性 / surefire 插件块全摘，全仓零命中；约定落进 `backend/CLAUDE.md` §10「集成测试环境」。实测：323 全绿、全量 828s→**634s**、迁移 186s→**3.4s**、容器创建日志归零、无环境时明确连接失败（`bs-nacos-test` 停掉即报）。**全 effort 验收达成，无后续票**。细节见票 04 Answer。

## Not yet specified

- **`.scratch/marketing-impl` 票 07 与本 effort 撞同一份文件的两处**：① 它写「`init.sql` **原地不动**」，但该文件已被 `git mv` 成 `schema.sql`（营销落地时应改 `schema.sql`，或按它自己那条「新建 `init-marketing.sql`」走）；② 它也要编辑 `docker-compose.yml` 头注释（加 `init-marketing.sql` 那一行），同期落地需注意同一段注释的合并。**两处都未动那张票**，留给其归属 effort。营销三个 schema 是否进 `init-test.sql` 属 Out of scope。
- 起环境脚本的路径/形态与测试栈两个容器名已由票 01 落定（见 Decisions），不再开放。

## Out of scope

（同 [spec](spec.md) Out of scope：那 9.6 分钟的用例执行耗时优化、Nacos / RocketMQ 的 namespace 方案、CI 适配、三个营销服务的测试、Testcontainers reuse、业务代码与业务测试断言、数据库 schema 的任何变更。）
