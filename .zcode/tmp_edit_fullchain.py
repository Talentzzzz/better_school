p='bs-app/src/test/java/com/betterschool/integration/errand/ErrandFullChainIntegrationTest.java'
s=open(p,encoding='utf-8').read()

s=s.replace("""/**
 * 全链路编排汇总（票 07）：
 * 初筛命中改文案重发成功；支付 → 消费者通知 → 裁决 → 对外可见/退款 的完整编排（通过/拒绝双链）；
 * outbox 定时扫描兜底重发未发送消息（测试环境无 broker：重试计数可观察）。
 */""",
"""/**
 * 全链路编排汇总（票 07）：
 * 支付 → 消费者通知 → 对外可见 的完整编排；
 * outbox 定时扫描兜底重发未发送消息（测试环境无 broker：重试计数可观察）。
 */""")

s=s.replace("""    // ---------- 初筛命中 → 改文案重发 ----------

    @Test
    void 初筛命中改文案重发成功() {
        Session user = registerAndLogin("13800007001", "发单用户");

        JsonNode rejected = postJson("/api/errand/orders", errandBody("帮我代考一下", null), user);
        assertThat(rejected.path("code").asText()).as("初筛命中应失败：%s", rejected).isEqualTo("500");
        assertThat(orderCount()).as("命中订单不应落库").isZero();

        JsonNode resent = postJson("/api/errand/orders", errandBody("帮我取个快递", null), user);
        assertSuccess(resent, "改文案重发");
        String orderId = resent.path("data").asText();
        assertUuidId(orderId);
        assertThat(statusOf(orderId)).as("改文案后应正常创建待支付").isEqualTo("PENDING_PAYMENT");
    }

    // ---------- 完整编排：支付 → 消费者通知 → 裁决 ----------

    @Test
    void 通过链路编排_支付通知裁决可见() {
        Session author = registerAndLogin("13800007002", "发单作者");
        String orderId = createPaidOrder(author, List.of("http://minio.test/bs-files/uploads/a.png"));
        Session admin = adminSession();

        // 1. 支付落 outbox → 消费者直接驱动 → 作者收到"进入人工审核"通知
        String payload = pendingPayloadOf(orderId);
        errandPaidConsumer.onMessage(payload);
        assertThat(notificationsOf(author.userId(), "ORDER_EVENT", "人工审核"))
                .as("支付后作者应收到进入人工审核通知").hasSize(1);

        // 2. 管理员裁决通过 → PUBLISHED + 对外可见 + 作者通知
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "ERRAND", "targetId", orderId, "approve", true), admin), "裁决通过");

        assertThat(statusOf(orderId)).isEqualTo("PUBLISHED");
        assertThat(listPublicOrderIds()).as("通过后应对外可见").contains(orderId);
        assertThat(notificationsOf(author.userId(), "AUDIT_RESULT", "通过审核"))
                .as("作者应收到通过通知").hasSize(1);

        JsonNode detail = getJson("/api/errand/orders/" + orderId, null);
        assertSuccess(detail, "访客看详情");
        assertThat(detail.path("data").path("images").get(0).asText())
                .as("详情应带出配图").isEqualTo("http://minio.test/bs-files/uploads/a.png");

        // 3. 状态流转事件流水（票 05 验收项 2）：创建 → 支付 → 裁决通过，顺序即 id 递增
        assertThat(eventTypesOf(orderId)).as("事件流水应完整记录这一次编排的每次流转")
                .containsExactly("CREATED", "PAID", "REVIEW_APPROVED");
        assertThat(eventFromStateOf(orderId, "CREATED")).as("创建事件无前态").isNull();
        assertThat(eventFromStateOf(orderId, "PAID")).as("支付事件前态取业务行真值").isEqualTo("PENDING_PAYMENT");
        assertThat(eventRowOf(orderId, "PAID"))
                .as("支付事件：PENDING_PAYMENT→PENDING_REVIEW、操作方 PUBLISHER")
                .containsEntry("to_state", "PENDING_REVIEW")
                .containsEntry("actor_role", "PUBLISHER")
                .containsEntry("actor_id", author.userId());
        assertThat(eventRowOf(orderId, "REVIEW_APPROVED"))
                .as("裁决事件：PENDING_REVIEW→PUBLISHED、操作方 ADMIN")
                .containsEntry("from_state", "PENDING_REVIEW")
                .containsEntry("to_state", "PUBLISHED")
                .containsEntry("actor_role", "ADMIN");
    }

    @Test
    void 拒绝链路编排_支付通知裁决退款理由() {
        Session author = registerAndLogin("13800007003", "发单作者");
        String orderId = createPaidOrder(author, null);
        Session admin = adminSession();

        errandPaidConsumer.onMessage(pendingPayloadOf(orderId));
        String reason = "配图模糊无法辨认需求";
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "ERRAND", "targetId", orderId, "approve", false, "reasonCode", "OTHER", "supplement", reason), admin),
                "裁决拒绝");

        assertThat(statusOf(orderId)).isEqualTo("REJECTED");
        assertThat(listPublicOrderIds()).as("被拒订单不对外可见").doesNotContain(orderId);

        assertThat(countWalletTransactions(orderId, "UNFROZEN")).as("拒绝应解冻退款").isEqualTo(1);
        BigDecimal balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, author.userId());
        assertThat(balance).as("冻结金额应全额退回").isEqualByComparingTo("1000.00");

        List<String> notices = notificationsOf(author.userId(), "AUDIT_RESULT", reason);
        assertThat(notices).as("拒绝通知应含理由").hasSize(1);
        JsonNode detail = getJson("/api/errand/orders/" + orderId, author);
        assertThat(detail.path("data").path("rejectReason").asText()).as("作者详情应带出理由").isEqualTo("其他：" + reason);

        // 状态流转事件流水（票 05 验收项 2）：拒绝事件落 REJECTED，且拒绝理由进事件的 reason 列
        assertThat(eventTypesOf(orderId)).as("创建 → 支付 → 审核驳回").containsExactly("CREATED", "PAID", "REVIEW_REJECTED");
        assertThat(eventRowOf(orderId, "REVIEW_REJECTED"))
                .as("驳回事件：PENDING_REVIEW→REJECTED、操作方 ADMIN、原因类型 REVIEW_REJECT")
                .containsEntry("from_state", "PENDING_REVIEW")
                .containsEntry("to_state", "REJECTED")
                .containsEntry("actor_role", "ADMIN")
                .containsEntry("reason_type", "REVIEW_REJECT");
        assertThat(eventReasonOf(orderId, "REVIEW_REJECTED"))
                .as("事件 reason 列应落拒绝理由（全站统一格式「枚举文案：补充说明」，原先该列恒 null）")
                .isEqualTo("其他：" + reason);
    }
""",
"""    // ---------- 完整编排：支付 → 消费者通知 → 可见 ----------

    @Test
    void 支付链路编排_通知可见() {
        Session author = registerAndLogin("13800007002", "发单作者");
        String orderId = createPaidOrder(author, List.of("http://minio.test/bs-files/uploads/a.png"));

        // 1. 支付落 outbox → 消费者直接驱动 → 作者收到"支付成功"通知
        String payload = pendingPayloadOf(orderId);
        errandPaidConsumer.onMessage(payload);
        assertThat(notificationsOf(author.userId(), "ORDER_EVENT", "支付成功"))
                .as("支付后作者应收到支付成功通知").hasSize(1);

        // 2. 支付即 PUBLISHED + 对外可见
        assertThat(statusOf(orderId)).isEqualTo("PUBLISHED");
        assertThat(listPublicOrderIds()).as("支付后应对外可见").contains(orderId);

        JsonNode detail = getJson("/api/errand/orders/" + orderId, null);
        assertSuccess(detail, "访客看详情");
        assertThat(detail.path("data").path("images").get(0).asText())
                .as("详情应带出配图").isEqualTo("http://minio.test/bs-files/uploads/a.png");

        // 3. 状态流转事件流水（票 05 验收项 2）：创建 → 支付，顺序即 id 递增
        assertThat(eventTypesOf(orderId)).as("事件流水应完整记录这一次编排的每次流转")
                .containsExactly("CREATED", "PAID");
        assertThat(eventFromStateOf(orderId, "CREATED")).as("创建事件无前态").isNull();
        assertThat(eventFromStateOf(orderId, "PAID")).as("支付事件前态取业务行真值").isEqualTo("PENDING_PAYMENT");
        assertThat(eventRowOf(orderId, "PAID"))
                .as("支付事件：PENDING_PAYMENT→PUBLISHED、操作方 PUBLISHER")
                .containsEntry("to_state", "PUBLISHED")
                .containsEntry("actor_role", "PUBLISHER")
                .containsEntry("actor_id", author.userId());
    }

    @Test
    void 取消链路编排_解冻退款() {
        Session author = registerAndLogin("13800007003", "发单作者");
        String orderId = createPaidOrder(author, null);

        // 已支付（PUBLISHED，冻结中）→ 作者取消 → 解冻退款
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel", null, author), "取消订单");

        assertThat(statusOf(orderId)).isEqualTo("CANCELLED");
        assertThat(listPublicOrderIds()).as("已取消订单不对外可见").doesNotContain(orderId);
        assertThat(countWalletTransactions(orderId, "UNFROZEN")).as("取消应解冻退款").isEqualTo(1);
        BigDecimal balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, author.userId());
        assertThat(balance).as("冻结金额应全额退回").isEqualByComparingTo("1000.00");
    }
""")

open(p,'w',encoding='utf-8',newline='').write(s)
print("ok fullchain")
