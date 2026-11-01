# 票 17 验收 1：三服务全量测试

跑法（每服务一个子代理，跨服务并行、同服务内绝不并发——票 05 的教训）：
`mvn -o test`，cwd 分别是 `backend/better-school` / `backend/marketing` / `backend/timeout-center`。

| 服务 | 命令 | tests | failures | errors | skipped | 结果 | 耗时 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| better-school（单体） | `mvn -o test` | **230** | 0 | 0 | 0 | BUILD SUCCESS | 10:58 |
| marketing | `mvn -o test` | **80** | 0 | 0 | 0 | BUILD SUCCESS | 2:58 |
| timeout-center | `mvn -o test` | **42** | 0 | 0 | 0 | BUILD SUCCESS | 2:35 |
| **合计** | | **352** | **0** | **0** | **0** | | |

## 分布

- better-school：只有 `bs-app` 有测试源码（44 个 `*Test` 类 → 44 份 surefire 报告，无一静默跳过），
  其余 6 个模块无测试。**230 = 票 14/15 的 230**，零回归。
- marketing：只有 `marketing-app` 有测试（17 个类 / 80 用例），其余 6 模块 "No tests to run"。
  **80 = 票 14 的 80**，零回归。
- timeout-center：`timeout-center-domain` 5 + `timeout-center-app` 37 = **42**（9 个类）。

## 环境与噪声

- 测试栈（测试专用 Nacos 18848 / namesrv 19876、MySQL 13306、Redis 16379、MinIO 19000）在跑，**无需重建测试库**：
  三个测试库的表结构都与 `schema.sql` 一致，全程没有出现 `Unknown column` / 表缺失 / 无默认值类错误。
- 预期噪声（与既定测试栈口径一致，不影响任何用例）：单体侧 outbox 发送因**测试栈没有 broker** 报
  `No route info of this topic`（被 `OutboxMessageSender` catch 住）、ES 指向刻意不可达的端口。
- 包装脚本把 Maven 写在 stderr 的 WARN 映射成 shell `exit code 1`（PowerShell 的 `NativeCommandError` 假象）；
  三个工程的 Maven 自身结论都是 `BUILD SUCCESS`，且 surefire XML 的 0 failures / 0 errors 独立佐证。
