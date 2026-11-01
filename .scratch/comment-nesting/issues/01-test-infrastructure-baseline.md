# 01: 测试设施 + 评论区现状基线

**What to build:** 为后端建立仓库第一批测试设施，并对**现有**平铺评论功能锁定行为基线。引入 spring-boot-starter-test 与 Testcontainers（MySQL、Redis 版本对齐 docker 编排文件中的镜像），测试统一走 trigger 层入口（HTTP 端点），建库走初始化 SQL。基线覆盖：发布评论（敏感词命中→PENDING_REVIEW 不可见 / 未命中→NORMAL 可见）、评论列表分页（现有 total 恒 0 的缺陷**如实断言现状**并标注 TODO，供 04 票替换）、删除评论（现状是物理删除，如实断言）。本票是纯 prefactor，为后续所有票的红绿切片铺路。

规格：`.scratch/comment-nesting/spec.md`（Testing Decisions 节）；术语：`CONTEXT.md`。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] Maven 标准生命周期一键跑测试，Testcontainers 自动起 MySQL/Redis 容器
- [x] 基线测试覆盖现有评论的发布/列表/删除 HTTP 行为（访客 + 登录态）
- [x] 测试只断言外部行为（HTTP 响应 + 数据库可观察状态），不 mock 内部调用
- [x] total 缺陷与物理删除行为以"现状断言 + TODO 标注"固化
- [x] 测试设施的组织方式留有说明，作为后续功能测试的先例

## Answer

**设施**：`bs-app/src/test/java/com/betterschool/integration/`——基类 `support/CommunityApiTestBase`（Testcontainers 起 `mysql:9.7` + `redis:8.8.0` + RocketMQ namesrv，镜像对齐 docker-compose；HTTP 走 TestRestTemplate 真实入口；登录态走 send-code → Redis 读码 → register 真实链路；@BeforeEach TRUNCATE 业务表隔离，sensitive_word 例外——词表启动时进内存，测试直接用 init.sql 种子词）。约定详见 `backend/docs/testing.md`。

**基线**：`integration/community/PostCommentBaselineIntegrationTest`，7 个用例覆盖发布可见性（敏感词命中→PENDING_REVIEW 他人不可见/未命中→NORMAL 可见+计数）、分页（id 升序切片）、删除（作者/帖主/无关人 403/访客 401）。两处现状断言带 `TODO(v2-04)`：列表 total 恒 0（PostController.listComments 硬编码）、删除为物理 DELETE 行消失。

**踩坑记录**（对后续票有影响）：
- Testcontainers 需 2.0.4+（Docker Engine 29 拒绝旧 docker-java 的请求）；`withCopyFileToContainer` 在 TC 2.x 时序晚于 MySQL entrypoint 初始化，init.sql 必须 bind mount
- `sql/init.sql` 头部补了 `SET NAMES utf8mb4`：容器 entrypoint 的 mysql CLI 默认 latin1，中文敏感词种子乱码入库会导致词匹配失效（compose 注释里的同一个坑，现在测试链路也踩到了）
- RocketMQ 消费者启动失败会炸 Spring 上下文，测试基类起 namesrv 容器给真地址；broker 缺席的发送失败被 OutboxMessageSender 捕获，不影响被测行为
