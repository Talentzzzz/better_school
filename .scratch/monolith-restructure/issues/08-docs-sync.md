# 08 文档同步与前端收口

Type: task
Status: open
Blocked by: 06, 07

## Question

执行：

1. `backend/CLAUDE.md` 更新为单体口径（模块布局、单应用单库、infra 包结构规范：adapter/{port, repository} + 平级技术包，技术包→adapter 禁止反向依赖）；
2. `backend/docs/architecture`（及原 `marketing/docs` 内容归并）更新单体架构图与说明；schema 文档同步合库结果；
3. `front` 的 vite proxy 删除 `/api/points`、`/api/lottery` 分流规则，统一指向单后端 `/api`；
4. 本地图收尾：核对目的地达成，把剩余 fog 清理或票化。

完成标准：文档与代码事实一致；独立 commit。

## Answer

（待执行后记录 commit hash 与要点）
