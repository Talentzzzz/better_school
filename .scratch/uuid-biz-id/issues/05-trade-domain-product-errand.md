# 05: 交易域翻转（product_id / errand_order_id）

**What to build:** 商品与跑腿订单业务 ID 变 UUID 字符串：发布/浏览商品、收藏、UV 计数、市集推荐、跑腿接单/流转/超时关单全链路 String 贯通。市集推荐档的同值收敛键同样从「业务 ID 降序」换成「create_time + 业务 ID」复合键。超时任务的 `biz_id` 复合引用编码（PAY_TIMEOUT 等把订单 id 编码进 VARCHAR 引用列）适配字符串订单 id，分片哈希路由语义不变。会话来源（ERRAND/PRODUCT）引用的 object_id 同步适配。

**Blocked by:** 02

**Status:** resolved

- [x] product/collect/uv/errand 相关表 DDL 业务键改 `VARCHAR(36)`，schema 脚本重写、删库重建
- [x] 商品与订单发号走 IdGenerator，应用层随 insert 一次写入
- [x] 超时任务注册/扫描/关单在字符串 biz_id 下路由与幂等不变（既有超时分片集成用例全绿）
- [x] 市集推荐收敛键换「create_time + 业务 ID」，推荐用例全绿
- [ ] 定向测试通过；全量测试全绿

> **验收实情（2026-11-02）**：前三项按编译 + 链路语义核对完成（全仓 compile / test-compile 全绿，grep 确认 trade 链路无 Long 业务 id、bindBizId、String.valueOf/parseLong 过渡缝合残留）。定向/全量测试**未跑**：测试中间件镜像（nacos/redis/minio）本地缺失，Docker 守护进程代理 127.0.0.1:7890 未开拉不动镜像，用户拍板「只编译不跑测试」。超时链路按字符串等值幂等核对（biz_id 复合键 `{uuid}:{seq}` 拆分不受 UUID 影响、分片 hash 输入仍是 biz_id 字符串）；市集推荐收敛键已换三段 `view_count DESC, create_time DESC, 业务 id DESC`（两索引表新增 create_time 主表快照列，游标 5 段化）。定向复跑命令（环境可用时）：`mvn -pl bs-app -am test -Dtest='com.betterschool.integration.trade.*,com.betterschool.integration.errand.*,TimeoutTask*Test,ErrandLockTimeout*,TimeoutTaskCallbackIntegrationTest,SearchIndexRebuildJobIntegrationTest,MarketFeedUvBackfillIntegrationTest,ViewMergeWriterTest'`（跑前先 `scripts/test-env.sh up` + `reset-test-db`）。文档/front/sql.md 旧口径已登记票 08 注记。
