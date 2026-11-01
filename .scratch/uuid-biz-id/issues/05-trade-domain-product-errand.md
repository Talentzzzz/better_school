# 05: 交易域翻转（product_id / errand_order_id）

**What to build:** 商品与跑腿订单业务 ID 变 UUID 字符串：发布/浏览商品、收藏、UV 计数、市集推荐、跑腿接单/流转/超时关单全链路 String 贯通。市集推荐档的同值收敛键同样从「业务 ID 降序」换成「create_time + 业务 ID」复合键。超时任务的 `biz_id` 复合引用编码（PAY_TIMEOUT 等把订单 id 编码进 VARCHAR 引用列）适配字符串订单 id，分片哈希路由语义不变。会话来源（ERRAND/PRODUCT）引用的 object_id 同步适配。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] product/collect/uv/errand 相关表 DDL 业务键改 `VARCHAR(36)`，schema 脚本重写、删库重建
- [ ] 商品与订单发号走 IdGenerator，应用层随 insert 一次写入
- [ ] 超时任务注册/扫描/关单在字符串 biz_id 下路由与幂等不变（既有超时分片集成用例全绿）
- [ ] 市集推荐收敛键换「create_time + 业务 ID」，推荐用例全绿
- [ ] 定向测试通过；全量测试全绿
