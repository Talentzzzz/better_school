package com.betterschool.integration.trade;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.trigger.job.TradeExpireScanJob;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全链路集成测试汇总（票 07）：在唯一测试 seam（HTTP 直驱 + 真实库 + 消费者/定时任务直调）上
 * 对闲置审核写全场景回归，覆盖商品先审后售时序。
 * 只断言外部行为（接口响应、库内状态、钱包流水、通知结果），不断言实现细节。
 */
class TradeFullChainIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private TradeExpireScanJob tradeExpireScanJob;

    // ---------- 初筛命中 → 提交失败不落库 → 改文案重发 ----------

    @Test
    void 初筛命中提交失败不落库改文案重发成功含草稿提交命中停留草稿() {
        Session seller = registerAndLogin("13800009001", "卖家1");

        // 直接发布命中：失败不落库
        JsonNode productHit = postJson("/api/trade/products", productBody("出售作弊神器"), seller);
        assertThat(productHit.path("code").asText()).as("商品初筛命中应失败：%s", productHit).isEqualTo("500");
        assertThat(productHit.path("info").asText()).contains("违规词汇");
        assertThat(countRows("product")).as("命中商品不应落库").isZero();

        // 改文案重发：商品进待审
        JsonNode productRetry = postJson("/api/trade/products", productBody("出售九成新吉他"), seller);
        assertSuccess(productRetry, "改文案重发商品");
        assertThat(productStatus(productRetry.path("data").asText())).isEqualTo("PENDING_REVIEW");

        // 草稿提交命中：停留草稿，可改后重新提交
        String draftId = saveProductDraft(seller, "九成新台灯");
        updateProductDraft(seller, draftId, "九成新台灯", "可代考复习资料");
        JsonNode draftHit = postJson("/api/trade/products/" + draftId + "/submit", null, seller);
        assertThat(draftHit.path("code").asText()).as("草稿提交初筛命中应失败：%s", draftHit).isEqualTo("500");
        assertThat(productStatus(draftId)).as("命中后停留草稿").isEqualTo("DRAFT");

        updateProductDraft(seller, draftId, "九成新台灯", "去年考证用过的资料");
        assertSuccess(postJson("/api/trade/products/" + draftId + "/submit", null, seller), "改后重新提交");
        assertThat(productStatus(draftId)).as("改后提交应进待审").isEqualTo("PENDING_REVIEW");
    }

    // ---------- 商品通过链路：发布 → 待审 → 看图裁决通过 → 对外可见 + 通知 ----------

    @Test
    void 商品通过链路_发布待审看图裁决通过对外可见并通知() {
        Session seller = registerAndLogin("13800009003", "卖家2");
        String productId = createProduct(seller, "九成新吉他", List.of(
                "http://minio/bs-files/it/f-1.jpg", "http://minio/bs-files/it/f-2.jpg"));
        Session admin = adminSession();

        // 管理员从队列带出配图看图裁决
        JsonNode queueItem = queueItemOf("PRODUCT", productId, admin);
        assertThat(queueItem.path("images")).as("队列应带出配图").hasSize(2);
        JsonNode detail = getJson("/api/moderation/queue/PRODUCT/" + productId, admin);
        assertSuccess(detail, "审核详情");
        assertThat(detail.path("data").path("images")).as("详情应带出配图").hasSize(2);

        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", true), admin), "裁决通过");

        assertThat(productStatus(productId)).isEqualTo("ON_SALE");
        assertThat(productIdsOf(getJson("/api/trade/products", null)))
                .as("通过后应对外可见").contains(productId);
        assertThat(notificationsOf(seller.userId(), "AUDIT_RESULT", "通过审核"))
                .as("作者应收到通过通知").hasSize(1);
    }

    // ---------- 商品拒绝链路：理由必填 → REJECTED 不可见 + 通知含理由 → 转草稿重提 ----------

    @Test
    void 商品拒绝链路_理由必填_不可见含理由通知_转草稿重提两轮记录不冲突() {
        Session seller = registerAndLogin("13800009004", "卖家3");
        Session other = registerAndLogin("13800009005", "路人1");
        String productId = createProduct(seller, "九成新滑板", List.of());
        Session admin = adminSession();

        // 理由必填
        JsonNode noReason = postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", false), admin);
        assertThat(noReason.path("code").asText()).as("拒绝理由必填：%s", noReason).isEqualTo("400");
        assertThat(productStatus(productId)).isEqualTo("PENDING_REVIEW");

        String reason = "配图与描述不符";
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", false, "reasonCode", "OTHER", "supplement", reason), admin),
                "裁决拒绝");
        assertThat(productStatus(productId)).isEqualTo("REJECTED");
        assertThat(productIdsOf(getJson("/api/trade/products", other)))
                .as("被拒商品不对外可见").doesNotContain(productId);
        assertThat(notificationsOf(seller.userId(), "AUDIT_RESULT", reason))
                .as("拒绝通知应含理由").hasSize(1);
        JsonNode ownerDetail = getJson("/api/trade/products/" + productId, seller);
        assertThat(ownerDetail.path("data").path("rejectReason").asText())
                .as("详情应带出理由").isEqualTo("其他：" + reason);

        // 转草稿 → 改后重提 → 第二次审核记录不冲突（两轮并存）
        assertSuccess(postJson("/api/trade/products/" + productId + "/to-draft", null, seller), "被拒转草稿");
        assertThat(productStatus(productId)).isEqualTo("DRAFT");
        updateProductDraft(seller, productId, "九五新单板滑板", "换了描述重新提");
        assertSuccess(postJson("/api/trade/products/" + productId + "/submit", null, seller), "草稿重新提交");
        assertThat(productStatus(productId)).isEqualTo("PENDING_REVIEW");

        String secondReason = "重提后内容仍不合规";
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", false, "reasonCode", "OTHER", "supplement", secondReason), admin),
                "第二次拒绝");
        assertThat(productStatus(productId)).isEqualTo("REJECTED");
        List<Integer> rounds = jdbcTemplate.queryForList(
                "SELECT review_round FROM content_review WHERE target_type = 'PRODUCT' AND target_id = ? "
                        + "AND action = 'REJECT' ORDER BY review_round", Integer.class, productId);
        assertThat(rounds).as("两次审核记录轮次互不冲突").containsExactly(1, 2);
    }

    // ---------- 撤回/草稿：待审撤回进草稿、可编辑、删除；下架 → 重提 ----------

    @Test
    void 撤回与草稿箱_待审撤回编辑删除与下架重提() {
        Session seller = registerAndLogin("13800009011", "卖家4");

        // 商品待审撤回 → 草稿可编辑 → 删除
        String productId = createProduct(seller, "九成新台灯", List.of());
        assertSuccess(postJson("/api/trade/products/" + productId + "/withdraw", null, seller), "商品待审撤回");
        assertThat(productStatus(productId)).as("撤回进草稿箱").isEqualTo("DRAFT");
        updateProductDraft(seller, productId, "九五新护眼台灯", "撤回后改了文案");
        assertSuccess(deleteJson("/api/trade/products/" + productId, seller), "删除草稿");
        assertThat(productStatus(productId)).isEqualTo("DELETED");

        // 商品在售下架 → 草稿 → 重提回待审
        String onSaleId = createApprovedProduct(seller, "九成新吉他");
        assertSuccess(postJson("/api/trade/products/" + onSaleId + "/takedown", null, seller), "在售商品下架");
        assertThat(productStatus(onSaleId)).as("下架汇入草稿箱").isEqualTo("DRAFT");
        updateProductDraft(seller, onSaleId, "九五新单板吉他", "下架后改了文案");
        assertSuccess(postJson("/api/trade/products/" + onSaleId + "/submit", null, seller), "下架后重提");
        assertThat(productStatus(onSaleId)).as("重提回待审").isEqualTo("PENDING_REVIEW");
    }

    // ---------- 编辑语义：改价立即生效；改内容回待审 ----------

    @Test
    void 编辑语义_改价立即生效_改内容回待审() {
        Session seller = registerAndLogin("13800009013", "卖家5");
        Session other = registerAndLogin("13800009014", "路人2");
        Session admin = adminSession();

        // 商品：改价立即生效不进审核
        String productId = createProduct(seller, "九成新台灯", List.of());
        approveProduct(admin, productId);
        assertSuccess(putJson("/api/trade/products/" + productId, Map.of("price", 29.9), seller), "仅改价");
        assertThat(productStatus(productId)).as("改价状态不变").isEqualTo("ON_SALE");
        assertThat(getJson("/api/trade/products/" + productId, other).path("data").path("price").asDouble())
                .as("价格立即生效").isEqualTo(29.9);

        // 商品：改内容回待审且期间对他人隐藏，通过后恢复
        assertSuccess(putJson("/api/trade/products/" + productId,
                Map.of("title", "专业级台灯", "description", "改后的新文案"), seller), "改内容");
        assertThat(productStatus(productId)).as("改内容应回待审").isEqualTo("PENDING_REVIEW");
        assertThat(productIdsOf(getJson("/api/trade/products", other)))
                .as("重审期间对他人隐藏").doesNotContain(productId);
        assertThat(getJson("/api/trade/products/" + productId, seller).path("data").path("title").asText())
                .as("重审期间本人可见新内容").isEqualTo("专业级台灯");
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", true), admin), "重审通过");
        assertThat(productStatus(productId)).isEqualTo("ON_SALE");
        assertThat(productIdsOf(getJson("/api/trade/products", other)))
                .as("通过后恢复可见").contains(productId);
    }

    // ---------- 下架/到期：商品进草稿 ----------

    @Test
    void 下架到期扫描_商品进草稿() {
        Session seller = registerAndLogin("13800009016", "卖家6");
        String productId = createApprovedProduct(seller, "九成新台灯");

        jdbcTemplate.update("UPDATE product SET expire_at = DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE product_id = ?", productId);
        tradeExpireScanJob.scan(null);

        assertThat(productStatus(productId)).as("到期商品进草稿箱而非终态").isEqualTo("DRAFT");
        assertThat(productIdsOf(getJson("/api/trade/products", null))).doesNotContain(productId);
    }

    // ---------- 24h 待审超时提醒：触发、去重、无自动裁决 ----------

    @Test
    void 待审超24h提醒触发去重且无自动裁决() {
        Session seller = registerAndLogin("13800009019", "卖家7");
        Session admin = registerAndLogin("13800009021", "管理员1");
        Session auditor = registerAndLogin("13800009022", "审核员1");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", admin.userId());
        jdbcTemplate.update("UPDATE bs_user SET role = 'AUDITOR' WHERE user_id = ?", auditor.userId());

        String productId = createProduct(seller, "九成新滑板", List.of());
        jdbcTemplate.update("UPDATE product SET review_entered_at = DATE_SUB(NOW(), INTERVAL 25 HOUR) WHERE product_id = ?",
                productId);

        tradeExpireScanJob.scan(null);
        tradeExpireScanJob.scan(null);

        assertThat(countReviewReminders(admin.userId(), List.of(productId)))
                .as("管理员应收商品一条，重复扫描不重复").isEqualTo(1);
        assertThat(countReviewReminders(auditor.userId(), List.of(productId)))
                .as("审核员应收商品一条，重复扫描不重复").isEqualTo(1);
        // 提醒不改状态，绝不自动裁决
        assertThat(productStatus(productId)).isEqualTo("PENDING_REVIEW");
    }

    // ---------- 商品订单事件流水（票 05 验收项 2） ----------

    @Test
    void 商品订单状态流转逐条落事件流水且取消分支带原因类型() {
        Session seller = registerAndLogin("13800009030", "卖家8");
        Session buyer = registerAndLogin("13800009031", "买家1");
        Session admin = adminSession();
        String productId = createApprovedProduct(seller, "九成新台灯");

        // 下单 → 支付 → 交付 → 确认：四次流转各落一条事件
        String orderId = createProductOrder(buyer, productId);
        assertThat(eventTypesOf(orderId)).as("下单只落创建事件").containsExactly("CREATED");
        assertThat(eventFromStateOf(orderId, "CREATED")).as("创建事件无前态").isNull();
        assertThat(eventRowOf(orderId, "CREATED"))
                .as("创建事件：→PENDING_PAYMENT、操作方 BUYER")
                .containsEntry("to_state", "PENDING_PAYMENT")
                .containsEntry("actor_role", "BUYER")
                .containsEntry("actor_id", buyer.userId());

        assertSuccess(postJson("/api/trade/orders/" + orderId + "/pay", null, buyer), "买家支付");
        assertSuccess(postJson("/api/trade/orders/" + orderId + "/mark-delivered", null, seller), "卖家标记已交付");
        assertSuccess(postJson("/api/trade/orders/" + orderId + "/confirm", null, buyer), "买家确认收货");

        assertThat(eventTypesOf(orderId))
                .as("事件序即状态机路径，同订单内 id 递增")
                .containsExactly("CREATED", "PAID", "DELIVERED", "COMPLETED");
        assertThat(eventRowOf(orderId, "PAID"))
                .as("支付事件：PENDING_PAYMENT→PAID、操作方 BUYER")
                .containsEntry("from_state", "PENDING_PAYMENT")
                .containsEntry("to_state", "PAID")
                .containsEntry("actor_role", "BUYER");
        assertThat(eventRowOf(orderId, "DELIVERED"))
                .as("交付事件：PAID→DELIVERED、操作方 SELLER")
                .containsEntry("from_state", "PAID")
                .containsEntry("to_state", "DELIVERED")
                .containsEntry("actor_role", "SELLER")
                .containsEntry("actor_id", seller.userId());
        assertThat(eventRowOf(orderId, "COMPLETED"))
                .as("确认事件：DELIVERED→COMPLETED")
                .containsEntry("from_state", "DELIVERED")
                .containsEntry("to_state", "COMPLETED")
                .containsEntry("actor_role", "BUYER");

        // 另一单走取消：事件同样落表，且原因类型与操作方与正常链路可区分
        String cancelledOrderId = createProductOrder(buyer, createApprovedProduct(seller, "九成新耳机"));
        assertSuccess(postJson("/api/trade/orders/" + cancelledOrderId + "/cancel", null, buyer), "买家取消");
        assertThat(eventTypesOf(cancelledOrderId))
                .as("取消单只落创建 + 取消").containsExactly("CREATED", "CANCELLED");
        assertThat(eventRowOf(cancelledOrderId, "CANCELLED"))
                .as("取消事件带原因类型与操作方")
                .containsEntry("to_state", "CANCELLED")
                .containsEntry("actor_role", "BUYER")
                .containsEntry("reason_type", "BUYER_CANCEL");
    }

    // ---------- 既有跑腿/帖子/评论审核路径无回归 ----------

    @Test
    void 既有跑腿帖子评论审核路径无回归() {
        Session user = registerAndLogin("13800009023", "普通用户");
        Session admin = adminSession();

        // 跑腿：发布 → 支付 → 待审 → 通过 → PUBLISHED 可见 + 通知
        JsonNode create = postJson("/api/errand/orders", Map.of(
                "type", "EXPRESS_PICKUP", "description", "帮忙取个快递",
                "pickupLocation", "菜鸟驿站", "deliveryLocation", "6号楼楼下",
                "reward", 10, "timeLimit", "HOURS_2"), user);
        assertSuccess(create, "发布跑腿");
        String orderId = create.path("data").asText();
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, user), "跑腿支付");
        assertThat(errandStatusOf(orderId)).isEqualTo("PENDING_REVIEW");
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "ERRAND", "targetId", orderId, "approve", true), admin), "跑腿裁决通过");
        assertThat(errandStatusOf(orderId)).isEqualTo("PUBLISHED");
        JsonNode publicOrders = getJson("/api/errand/orders?page=1&pageSize=10", null);
        assertThat(StreamSupport.stream(publicOrders.path("data").path("list").spliterator(), false)
                .map(node -> node.path("orderId").asText())).contains(orderId);

        // 帖子：先审后发（票 01）——正常内容落库即待审，人工通过后公开；命中敏感词报错不落库
        String postId = publishPost(user, "正常帖子内容");
        JsonNode posts = getJson("/api/community/posts?page=1&pageSize=10", null);
        assertThat(StreamSupport.stream(posts.path("data").path("list").spliterator(), false)
                .map(node -> node.path("postId").asText())).contains(postId);

        String pendingPostId = postJson("/api/community/posts",
                Map.of("title", "讨论帖", "content", "正常待审内容"), user)
                .path("data").asText();
        assertThat(postStatusOf(pendingPostId)).as("先审后发：发布即进人工审核").isEqualTo("PENDING_REVIEW");
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", pendingPostId, "approve", true), admin), "帖子裁决通过");
        assertThat(postStatusOf(pendingPostId)).isEqualTo("NORMAL");

        // 评论：先审后发（ADR-0004）——初筛命中报错不落库，正常内容落库即待审，人工裁决通过转 NORMAL
        String normalCommentId = publishCommentAndGetId(postId, user, "顶一下");
        assertThat(commentStatusOf(normalCommentId)).isEqualTo("PENDING_REVIEW");

        JsonNode flagged = publishComment(postId, user, "帮我作弊一下");
        assertThat(flagged.path("code").asText()).as("初筛命中应报错").isEqualTo("500");

        driveCommentReview(normalCommentId);
        assertThat(commentStatusOf(normalCommentId)).as("人工裁决通过转 NORMAL").isEqualTo("NORMAL");
    }

    // ---------- 工具 ----------

    private Session cachedAdminSession;

    /** 管理员登录态（每用例缓存复用）：先注册（USER），改库提权后重新走验证码登录 */
    private Session adminSession() {
        if (cachedAdminSession != null) {
            return cachedAdminSession;
        }
        Session registered = registerAndLogin("13900009001", "管理员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", registered.userId());
        postJson("/api/user/send-code", Map.of("phone", "13900009001"), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + "13900009001").get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", "13900009001", "code", code), null);
        assertSuccess(loginResponse, "管理员重新登录");
        cachedAdminSession = new Session(loginResponse.path("data").path("token").asText(), registered.userId());
        return cachedAdminSession;
    }

    private Map<String, Object> productBody(String title) {
        return Map.ofEntries(
                Map.entry("title", title),
                Map.entry("description", "自用闲置，九成新"),
                Map.entry("images", List.of()),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS"));
    }

    private String createProduct(Session seller, String title, List<String> images) {
        JsonNode response = postJson("/api/trade/products", Map.ofEntries(
                Map.entry("title", title),
                Map.entry("description", "自用闲置，九成新"),
                Map.entry("images", images),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS")), seller);
        assertSuccess(response, "发布商品");
        String productId = response.path("data").asText();
        // uuid-biz-id 票 08 收口：发商品返回 id 必须是可解析的 UUID 字符串（ID 形态断言）
        assertUuidId(productId);
        assertThat(productStatus(productId)).as("先审后售：提交即待审").isEqualTo("PENDING_REVIEW");
        return productId;
    }

    private String createApprovedProduct(Session seller, String title) {
        String productId = createProduct(seller, title, List.of());
        approveProduct(adminSession(), productId);
        return productId;
    }

    private void approveProduct(Session admin, String productId) {
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", true), admin), "商品裁决通过");
        assertThat(productStatus(productId)).isEqualTo("ON_SALE");
    }

    private String saveProductDraft(Session seller, String title) {
        JsonNode save = postJson("/api/trade/products/draft", Map.of("title", title), seller);
        assertSuccess(save, "保存商品草稿");
        return save.path("data").asText();
    }

    private void updateProductDraft(Session seller, String productId, String title, String description) {
        assertSuccess(putJson("/api/trade/products/" + productId + "/draft", Map.ofEntries(
                Map.entry("title", title),
                Map.entry("description", description),
                Map.entry("images", List.of()),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS")), seller), "编辑商品草稿");
    }

    /** 审核队列中指定目标的条目（断言队列带出内容/配图用） */
    private JsonNode queueItemOf(String targetType, String targetId, Session admin) {
        JsonNode queue = getJson("/api/moderation/queue?targetType=" + targetType + "&page=1&pageSize=50", admin);
        assertSuccess(queue, "审核队列");
        return StreamSupport.stream(queue.path("data").path("list").spliterator(), false)
                .filter(node -> node.path("targetId").asText().equals(targetId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("目标 %s 不在 %s 审核队列中：%s".formatted(targetId, targetType, queue)));
    }

    private List<String> notificationsOf(String userId, String type, String keyword) {
        return jdbcTemplate.queryForList(
                "SELECT content FROM notification WHERE user_id = ? AND type = ? AND content LIKE ?",
                String.class, userId, type, "%" + keyword + "%");
    }

    private long countReviewReminders(String userId, List<String> targetIds) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'ORDER_EVENT' "
                        + "AND content LIKE '%24 小时%' AND ref_id IN ("
                        + targetIds.stream().map(id -> "'" + id + "'").reduce((a, b) -> a + "," + b).orElse("''") + ")",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    private String productStatus(String productId) {
        return jdbcTemplate.queryForObject("SELECT state FROM product WHERE product_id = ?", String.class, productId);
    }

    private String errandStatusOf(String orderId) {
        return jdbcTemplate.queryForObject("SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }

    private String postStatusOf(String postId) {
        return jdbcTemplate.queryForObject("SELECT state FROM post WHERE post_id = ?", String.class, postId);
    }

    /** 买家对已上架商品下单，返回商品订单业务 ID */
    private String createProductOrder(Session buyer, String productId) {
        JsonNode response = postJson("/api/trade/orders?productId=" + productId, null, buyer);
        assertSuccess(response, "商品下单");
        String orderId = response.path("data").asText();
        // uuid-biz-id 票 08 收口：下单返回 id 必须是可解析的 UUID 字符串（ID 形态断言），后续流转以同一字符串引用成功
        assertUuidId(orderId);
        return orderId;
    }

    /** 该商品订单的事件流水类型序（id 递增即状态流转顺序） */
    private List<String> eventTypesOf(String orderId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM product_order_event WHERE order_id = ? ORDER BY id", String.class, orderId);
    }

    /** 某类事件的行（一条链路里每类事件至多一条；取首条足够） */
    private Map<String, Object> eventRowOf(String orderId, String eventType) {
        return jdbcTemplate.queryForMap(
                "SELECT from_state, to_state, actor_id, actor_role, reason_type, reason "
                        + "FROM product_order_event WHERE order_id = ? AND event_type = ? ORDER BY id LIMIT 1",
                orderId, eventType);
    }

    /** 事件前态；无该事件行或该列为 NULL 都返回 null（创建事件即 NULL 前态） */
    private String eventFromStateOf(String orderId, String eventType) {
        List<String> states = jdbcTemplate.queryForList(
                "SELECT from_state FROM product_order_event WHERE order_id = ? AND event_type = ? ORDER BY id LIMIT 1",
                String.class, orderId, eventType);
        return states.isEmpty() ? null : states.get(0);
    }

    private int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private List<String> productIdsOf(JsonNode response) {
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .map(node -> node.path("productId").asText()).toList();
    }
}
