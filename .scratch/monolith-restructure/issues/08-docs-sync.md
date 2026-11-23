# 08 文档同步与前端收口

Type: task
Status: done

## Question

执行：

1. `backend/CLAUDE.md` 更新为单体口径（模块布局、单应用单库、infra 包结构规范：adapter/{port, repository} + 平级技术包，技术包→adapter 禁止反向依赖）；
2. `backend/docs/architecture`（及原 `marketing/docs` 内容归并）更新单体架构图与说明；schema 文档同步合库结果；
3. `front` 的 vite proxy 删除 `/api/points`、`/api/lottery` 分流规则，统一指向单后端 `/api`；
4. 本地图收尾：核对目的地达成，把剩余 fog 清理或票化。

完成标准：文档与代码事实一致；独立 commit。

## Answer

已完成，commit `3ba09c7`（24 files，+278/−319）+ 评审订正 `2fe964d`（sql.md §2 展开表数 19→17/未展开 5、GLOSSARY.md 头部单体口径、CLAUDE.md merge 树措辞）。要点：

1. **CLAUDE.md**：新增 §0「当前工程形态」（唯一 reactor `backend/better-school`、bs-* 七模块、单应用 bs-app、单库 better_school、marketing 命名空间退役、FqcnAnnotationBeanNameGenerator + @MapperScan 单基包）；§3 infra 树重写为票 06 终态（adapter/{port, repository} 两棵树 + 平级技术包 cache/dao/merge(count)/outbox/search/security/sharding/ws，**技术包→adapter 禁止反向依赖**，横域组件落 adapter/repository 根包——先例 CountFlushDispatcher；两套合并引擎并存不归并）。
2. **架构/schema 文档**：`architecture/marketing-services.md` 整篇重写为单体运行手册（端口表只剩 8080/50051/9999，旧 8081/50052/9998 已释放；Dubbo 双 Provider 保留现状、无 consumer；MQ 两撞名 topic 保留现状归后续 effort；购买次数三段式链路、契约变更纪律改单工程口径；`/api/points/ping` 改进程内直调 IUserRepository）。合库口径同步：backend/docs/{README,db/README,api/README,testing}.md（营销测试段改 bs-app 单层）、marketing/README 与 db/api/architecture 子文档头注、better-school/docs/db/{README,outbox}.md（outbox 表补 delay_level/delay_ms 两列）；根 `sql.md` 改单工程口径：全库 79 表（41 单体业务 + 16 超时分表 + 22 营销并入）、§1 outbox_message 换 marketing 超集形状（全库唯一一份）、§2 改「营销领域段（better_school 尾段）」并删重复 outbox DDL。
3. **front/vite.config.ts**：删除 `/api/points`、`/api/lottery` 两条 8081 分流，只剩 `/api`→8080 与 `/ws`。
4. **收尾**：dev-stack.ps1 单服务化（$Services 删 marketing 段、构建/起停/健康文案收敛，PS1 语法 parse OK）、run-app.sh 删 marketing svc（保留报错提示）、docker-compose 8089 注释更新、Points/RaffleApiTestBase 的 `bs_marketing_test` 注释订正为 `better_school_test`；map.md 已记「Decisions so far」并把票 08 遗留项从「Not yet specified」摘除——剩余 fog 仅 RocketMQ 链路进程内化一项（已有票化结论）。

**票化（fog→票）**：sql.md / marketing db 文档的 4 张未展开表（单体侧 `product_inventory`、`product_stock_event`；营销侧 `draw_count_purchase_order`、`draw_orphan_prereserve_order`）在文档中留「未展开表」注记并指向 schema.sql，逐表 DDL 补齐归后续文档票。

**核对结论**：文档与代码事实一致（端口/MapperScan/测试库名/PingController 实现均按源码核对）；不跑测试、不冒烟（effort 拍板边界，Java 侧仅注释变更）。
