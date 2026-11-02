# map — 跑腿订单审核机制重构（先付后审）

spec: `.scratch/errand-order-review/spec.md`；ADR: `docs/adr/0003-errand-pay-before-review.md`；词汇：`CONTEXT.md` 跑腿订单一节。

## Notes

- 状态机反转：`PENDING_PAYMENT → PENDING_REVIEW(已付款待审) → PUBLISHED / REJECTED`；动机是审核资源只花在已付款订单上（ADR-0003）。
- 支付事务内直接转待审 + outbox 只承担通知，不用消息承载状态迁移（避免卡死态）。
- 同步初筛硬拦截不落库，只覆盖标题+描述；配图仅人工看图裁决。
- 裁决收敛为统一的 decide() 用例。

## Decisions-so-far

- （访谈定稿，2026-09-06）spec.md 全量记录 13 项决策；票 01~07 待做。
- （票 01 resolved，2026-09-06）上传通道分层：`IFileStoragePort`（domain）→ `FileStoragePort`（infrastructure，MinIO SDK），校验收敛在 `FileUploadCase`，常量在 `Constants`（5MB / jpg,png,webp / ERRAND_MAX_IMAGES=6）；URL 为公开读直链 `minio.public-url/bucket/uploads/yyyyMM/uuid.ext`，桶自动建 + 公开读策略。集成测试的 MinIO 容器必须放 `CommunityApiTestBase` 基类（子类 @DynamicPropertySource 会分裂第二个 Spring 上下文，xxl-job handler 同 JVM 注册冲突）。
- （票 02 resolved，2026-09-06）存量迁移口径：旧链路 PENDING_REVIEW 行均为未支付订单（初筛命中转入、不可支付、无冻结），006 脚本统一转 CANCELLED，无资金动作。
- （票 02 resolved，2026-09-06）搜索同步消息从支付链路移除（支付后不再即公开可搜），转 PUBLISHED 的时机补落 `bs-search-sync`——归票 03 的裁决通过链路；票 02~03 之间旧审核编排（reviewApprove→PENDING_PAYMENT / reviewReject→CANCELLED 无退款）与新语义暂不一致，由票 03 收敛修复。
- （票 04 resolved，2026-09-06）通知类型均复用 `ORDER_EVENT`（"进入人工审核"作者通知 + 24h 待审超时管理员提醒），幂等走通知表 `uk_dedup`（新增 `INoticeService.sendSystem` 系统通知通道，dedup_key = `ORDER_EVENT:ERRAND_PAID:{orderId}` / `ORDER_EVENT:REVIEW_TIMEOUT:{orderId}:{reviewerId}`）。提醒收件人 = ADMIN + AUDITOR（票面写"管理员"，扩到审核员：审核队列操作者都该被提醒）。24h 判定用 `updated_at`（PENDING_REVIEW 由支付条件 UPDATE 翻转，updated_at 即进入待审时间）。
- （票 05 resolved，2026-09-06）rejectReason 读取链路：moderation 域新增 `IReviewService.rejectReason` → `IModerationRepository.findRejectReason`（content_review action=REJECT，uk 下至多一行取最新兜底）；case 层 `ErrandOrderCase.errandRejectReason`；可见性判定在 Controller（作者 + REJECTED 才 set），DTO 字段加 `@JsonInclude(NON_NULL)` 保证"他人不带"是字面缺字段（bs-api 因此补 jackson-annotations 依赖）。发布页传图/限 6 张/初筛报错 toast 已随票 01 就位，本票未再动。详情页配图网格展示为票 05 顺带补齐（spec 字面写"配图仅人工审核环节查看"，此处指审核手段上仅人工看图，不禁止作者在详情回看自己的配图；服务 User Story 2）。
- （票 06 resolved，2026-09-06）前端此前没有任何管理端页面，本票从零建工作台：路由 `/admin/review`（meta.roles 门控 ADMIN/AUDITOR，非授权重定向首页）+ Profile 页角色入口卡。裁决走既有 `POST /api/moderation/review`（AUDITOR/ADMIN 都可操作，内部转发统一 decide 用例），不用仅 ADMIN 的 `/api/admin/reviews/decide`——队列可见者含 AUDITOR，审核员应能直接裁决。队列 ERRAND 分支 ORDER BY 改 `updated_at`（= 进入待审时间，对齐票 04 的 24h 判定口径），POST/COMMENT 维持 created_at。票面"标题"在现有数据模型不存在（errand_order 无 title 列），队列条目展示 description（content）+ 悬赏 + 配图；队列/详情 DTO/PO/Entity 补 `images`（JSON 数组，infrastructure 反序列化，失败按空）。集成测试 `ErrandReviewQueueIntegrationTest` 覆盖配图带出、进入待审时间排序、裁决后移出队列。
- （票 07 resolved，2026-09-06）spec 场景清单票 01~06 已基本覆盖，本票补三处交叉场景（`ErrandFullChainIntegrationTest`）：①初筛命中→改文案重发成功（票 02 只测了命中不落库）；②支付→消费者通知→裁决→可见/退款 双链完整编排（各票分测，串成一条未测）；③outbox 定时扫描兜底——测试环境无 broker，发送必失败，可观察断言 = retry_count 增加、状态保持 PENDING；标记线程异步落库故用 5s 轮询。冒烟 `smoke-ticket07.py`（17 断言）：真实 compose + 新 jar 8080，通知走真实链路（outbox 即时发送 → RocketMQ → 消费者落库，docker exec mysql 轮询断言），覆盖 MinIO 上传/初筛拦截/队列配图/裁决通过可见/拒绝退款+理由（详情+通知）。冒烟资金断言口径：A 单 PUBLISHED 后其悬赏按设计冻结至完成（integration 已断言），拒绝单只断言"解冻恰好退回自身悬赏"，不断言"冻结清零"。

## Fog

- 通知类型：consumer 的"进入人工审核"与 24h 提醒分别复用现有类型还是新增，留实现票定。（票 04 已定：均复用 ORDER_EVENT）
- 旧 PENDING_REVIEW 存量数据的迁移口径（增量脚本里怎么处理），留 02 定。（票 02 已定：统一转 CANCELLED）
