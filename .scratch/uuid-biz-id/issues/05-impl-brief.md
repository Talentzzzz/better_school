# uuid-biz-id 票 05 实施简报（共享口径，三个执行方都必须先读）

任务：交易域业务 ID 翻转——product_id / errand_order_id / product_order_id 全链路 `Long/BIGINT → String/UUID(36)`。schema.sql 已由协调者改完（勿再动 backend/better-school/sql/**）。本文只讲共性口径，各自文件清单与专属注意点见各自的派单。

## 参考先例（必看）

- 票 03（内容域翻转）：`git show efb7f57 --stat`，需要看某个文件改法时 `git show efb7f57 -- <path>`。
- 票 04（会话域翻转）：`git show f060aae`。
- 发号先例：`CommunityRepository.save` / `ChatRepository.insertRoom`——repository 注入 `IIdGenerator`（`com.betterschool.types.id.IIdGenerator`，方法 `nextId()` 返回 36 位小写 UUID 字符串，Bean 已在 IdGeneratorConfig 注册，直接 `private final IIdGenerator idGenerator;` 构造注入），在 insert 前生成、随行一次写入。

## 全局硬口径

1. **所有业务 ID 类型 Long → String**：实体 / PO / VO / DTO / 仓储接口 / 域服务接口与实现 / case / Controller `@PathVariable` / DAO 方法参数 / mapper `resultType="long"→"string"`。物理主键 `id`（BIGINT AUTO_INCREMENT）保留不动，永不对外。
2. **发号**：repository 的 save/insert 路径在构建 PO 前调 `idGenerator.nextId()` 写入业务键；**删除** mapper 里 `bindBizId` 语句、`useGeneratedKeys`/`keyProperty`（若只服务于 bindBizId 回填）以及 repository 里的 `dao.bindBizId(po.getId())` / `entity.setXxx(po.getId())` 回写行；insert `VALUES(0, …)` 改 `VALUES(#{productId}, …)`。
3. **mapper XML**：业务键裸 SQL 数值比较改字符串（如 `parent_id > 0` 类隐式转换陷阱：数值 0 比较会把 UUID 隐式转 0，必须 `!= '0'` 带引号）；`resultType="long"` 的业务键查询改 `"string"`；`jdbcType` 如显式写了 BIGINT 改 VARCHAR。
4. **transition 缝合拆除**（票 03 预留给本票的）：`TradeRepository.recordProductView` 的 `String.valueOf(productId)`、`flushViewGroup` 开头的 `Long.parseLong(productIdKey)`、`saveSearchSyncMessage` 的 `String.valueOf`、审核池 `reviewTaskPoolStore.enter/close(..., String.valueOf(...))` → 直传 String；`ErrandRepository` 同款；`ReviewCase.numericTargetId`（PRODUCT/ERRAND 分支）、`ChatSendServiceImpl.numericRefId` 整体删除。
5. **注释口径**：改到哪行就把那行的旧口径注释（「业务 ID 与物理自增同值」「id≡product_id」等）一并改掉，注明 uuid-biz-id 票 05。
6. **不跑全量测试、不 git commit、不建新表列**（schema 已定稿）；类型翻转务必一次翻全，不留 `Long` 编译错。
7. 改动只限派单文件清单；清单外文件需要改时**先报告协调者**（写进你的完成报告），不要越界改。

## 跨方接口契约（已拍板，各方照此对齐）

- `ICreditAccountService.applyChange(String userId, CreditReasonTypeVO reasonType, String refId)`——refId 变 String。调用方传订单业务 ID 直接传；传物理流水 id（如 reportId）用 `String.valueOf(reportId)`。
- `ITradeRepository.listIdsAfter(String lastProductId, int limit) → List<String>`、跑腿同理：搜索同步重建游标从「物理 id > lastId」改为「业务 id 字典序」（SQL：`WHERE product_id > #{lastProductId} ORDER BY product_id LIMIT …`，初值空串）——照帖子侧票 03 先例。
- 市集推荐档（hot/热度）收敛键：`view_count DESC, create_time DESC, <业务id> DESC`。product_feed_index / errand_feed_index 已由协调者在 schema 加了 `create_time` 列（主表 create_time 快照，approve 事务随行写入），游标 VO 增补 create_time 段、谓词与 ORDER BY 三段展开（照帖子热榜三段先例）。
- 超时回调：`TimeoutTaskCallbackDispatcher` 里 PAY_TIMEOUT 族的 bizId 现在直接是订单业务 ID 字符串（不再 parseLong）；复合键 `{orderId}:{lockSeq}` 中 orderId 变 UUID 字符串——`indexOf(':')` 拆分不受影响（UUID 无冒号），`CompositeBizId` 第一段 Long → String。
- chat 卡片引用：`ICardRefQueryPort.findProductStatus(String productId) / findErrandStatus(String errandId)`；`ChatSendServiceImpl` PRODUCT/ERRAND 分支 refId 直传。
- 钱包：`wallet_detail.order_id` 已改 VARCHAR(36)，钱包侧携带 orderId 的 Java 签名同步 String；trade/errand 调用点传 String。
- ES：product/errand 搜索文档 id 字段 String（mapping 本就 keyword 预期不动，核实即可）。
