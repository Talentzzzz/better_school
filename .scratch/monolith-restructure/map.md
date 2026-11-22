# monolith-restructure — wayfinder map

## Destination

backend 回归单体：单一 Maven reactor（分层 7 模块 types/api/domain/case/infrastructure/trigger/app，单 Spring Boot 应用、单库），marketing 域群代码以**平级领域包**并入 `com.betterschool`（不存在"服务"命名空间，只有领域概念），infra 层统一为 `adapter/{port, repository}` + 平级技术包（dao/merge/ws/cache/search/mq/outbox/security/sharding，按需建）的包结构，全 reactor 编译/test-compile 绿，文档同步。**不跑测试、不做启动冒烟**。

## Notes

- DDD 分层项目；`domain/<域>` 包是未来再拆的边界，合并不改变领域内部结构。
- 用户已拍板（2026-11-23 charting 会话）：
  - 模块布局 = 保留分层 7 模块，marketing 的 7 个模块按层并入对应 `bs-*`；
  - marketing 代码平级铺开（`domain/raffle` 等，**无** marketing 命名空间层）；
  - 数据库合库、单数据源；跨域事务纪律（repo 边界、资金先行、唯一键幂等、孤儿扫描）不因合库改变；
  - infra 形状 = `adapter/{port, repository}` + 平级技术包，技术包按需建（不建空包），不允许技术包→adapter 反向依赖；port/repository 实现想用什么就从 dao/merge/ws/cache/search/mq 等平级包取用；
  - 动工前先提交票 B/C 存量改动；分阶段提交，每阶段全 reactor 编译绿；
  - 验收 = 编译/test-compile 绿 + 文档同步；**不跑测试、不冒烟**。
- 工具链：本机无 Maven/JDK21，用 `Z:/wt-draw-recon/tools/`（Maven 3.9.9 + Temurin JDK 21）。surefire 需联网（本 effort 不跑测试，编译建议联网跑）。
- git 身份：repo 级 TalentXD666 <a13683639985@163.com>。
- 本 effort 的票含执行票（task 型由 agent 直接落地），延续 tx-boundary-refactor 的惯例。
- tracker = 本地 markdown（`.scratch/monolith-restructure/`）。
- 已知事实：bs-domain 内是 `domain/<域>/adapter/{port, repository}` 接口层，与 infra 层 `adapter/{port, repository}` 实现同名双层；domain 层**不动**，重组只动 infrastructure 模块。

## Decisions so far

- [01 包名/类名冲突扫描](issues/01-name-collision-scan.md)：FQN 冲突 10 处（含 rocketmq 同包补丁类两侧完全同名）、简单名冲突 29 类名、反向依赖 1 处（marketing-infrastructure → bs-api）、adapter 双层包序相反（bs `adapter.X.<域>` vs marketing `<域>.adapter.X`）；完整清单在 research/01-name-collision.md。
- [02 合库/配置冲突扫描](issues/02-db-config-collision-scan.md)：同名表仅 outbox_message（marketing 侧超集）、marketing 无 sharding（SS 单表规则零改动覆盖）、配置必撞键 8 组（mybatis 需 `**` 递归）、xxl-job 撞名 1（outboxScan）+2 处 seed↔代码不一致、HTTP 前缀交集 0（且暴露 vite proxy 缺 2 个 marketing 前缀的存量缺口）、RocketMQ topic 撞名 2 个为既定事件流非错误；完整清单在 research/02-db-config.md。

## Not yet specified

- xxl-job 2 处 seed↔代码不一致（`postReviewTimeoutScan` seed 有代码无、`pointsBucketCompaction` 代码有 seed 无）——07 票执行时核对处置，是否单独票化待 04 拍板。
- `outbox_message` 双表归并方案（marketing 侧超集两列）——04 票拍板。

## Out of scope

- 测试与运行验证（用户拍板：不跑测试、不冒烟）。
- 未来按 domain 再拆微服务（仅保持可能性，不实施）。
- front 业务改动（仅改 vite proxy 指向单后端）。
