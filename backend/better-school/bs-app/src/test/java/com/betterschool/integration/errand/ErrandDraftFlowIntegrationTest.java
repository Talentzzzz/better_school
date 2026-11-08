package com.betterschool.integration.errand;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.trigger.job.ErrandTimeoutScanJob;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跑腿草稿链路（票 02）集成测试：草稿是服务端落行的私有态，可编辑全字段、可软删；
 * 两条创建入口并存（草稿入口落 DRAFT、直接发布落 PENDING_PAYMENT）；
 * 提交＝必填 + 同步初筛通过后转 PENDING_PAYMENT 且同事务注册 15 分钟支付超时；
 * 草稿态不起支付钟；软删 DELETED 从公开列表/详情/我的列表一律消失。
 */
class ErrandDraftFlowIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private ErrandTimeoutScanJob errandTimeoutScanJob;

    // ---------- 创建：草稿落 DRAFT、不注册支付超时、仅本人可见 ----------

    @Test
    void 草稿创建落DRAFT_不注册支付超时_仅本人可见() {
        Session author = registerAndLogin("13800003001", "草稿用户1");
        Session other = registerAndLogin("13800003002", "路人1");

        // 未填完（只有描述）也能存；且不做同步初筛
        JsonNode save = postJson("/api/errand/orders/draft", Map.of("description", "先写一半"), author);
        assertSuccess(save, "保存跑腿草稿");
        String orderId = save.path("data").asText();
        assertUuidId(orderId);

        assertThat(stateOf(orderId)).as("草稿入口应落 DRAFT").isEqualTo("DRAFT");
        assertThat(payTimeoutRegistrations(orderId)).as("草稿态不注册支付超时（支付钟只在提交后起算）").isZero();

        // 他人：公开列表 / 我的列表 / 详情全部看不到
        JsonNode publicList = getJson("/api/errand/orders?status=PUBLISHED", other);
        assertThat(orderIdsOf(publicList)).as("草稿不对外可见").doesNotContain(orderId);
        JsonNode otherMine = getJson("/api/errand/orders/mine?role=publish", other);
        assertThat(orderIdsOf(otherMine)).as("他人我的列表不含该草稿").doesNotContain(orderId);
        JsonNode otherDetail = getJson("/api/errand/orders/" + orderId, other);
        assertThat(otherDetail.path("code").asText()).as("他人看草稿详情应 404：%s", otherDetail).isEqualTo("404");

        // 本人：我的列表含草稿、详情可见
        JsonNode mine = getJson("/api/errand/orders/mine?role=publish", author);
        List<JsonNode> mineItems = itemsOf(mine).stream()
                .filter(node -> node.path("orderId").asText().equals(orderId)).toList();
        assertThat(mineItems).as("我的列表应含自己的草稿").hasSize(1);
        assertThat(mineItems.get(0).path("status").asText()).isEqualTo("DRAFT");

        JsonNode ownerDetail = getJson("/api/errand/orders/" + orderId, author);
        assertSuccess(ownerDetail, "本人看自己的草稿");
        assertThat(ownerDetail.path("data").path("description").asText()).isEqualTo("先写一半");
    }

    // ---------- 编辑：全字段可改；作者与状态位不变、不产生状态迁移 ----------

    @Test
    void 草稿可编辑全部内容字段_不改状态与作者() {
        Session author = registerAndLogin("13800003003", "草稿用户2");
        String orderId = saveDraft(author, Map.of("description", "占位"));

        JsonNode update = putJson("/api/errand/orders/" + orderId + "/draft", Map.ofEntries(
                Map.entry("type", "BUY"),
                Map.entry("description", "帮我买杯咖啡"),
                Map.entry("pickupLocation", "一食堂"),
                Map.entry("deliveryLocation", "6号楼302"),
                Map.entry("reward", 15),
                Map.entry("timeLimit", "HOURS_4"),
                Map.entry("images", List.of("http://minio/bs-files/it/d1.jpg", "http://minio/bs-files/it/d2.jpg"))
        ), author);
        assertSuccess(update, "编辑跑腿草稿");

        JsonNode data = getJson("/api/errand/orders/" + orderId, author).path("data");
        assertThat(data.path("type").asText()).isEqualTo("BUY");
        assertThat(data.path("description").asText()).isEqualTo("帮我买杯咖啡");
        assertThat(data.path("pickupLocation").asText()).isEqualTo("一食堂");
        assertThat(data.path("deliveryLocation").asText()).isEqualTo("6号楼302");
        assertThat(data.path("reward").asDouble()).isEqualTo(15);
        assertThat(data.path("images")).hasSize(2);
        assertThat(data.path("status").asText()).as("编辑不产生状态迁移").isEqualTo("DRAFT");

        // publisherId 与状态位不可改：落库侧核对作者不变、时限已刷新
        assertThat(publisherIdOf(orderId)).as("作者不可改").isEqualTo(author.userId());
        assertThat(timeLimitHoursOf(orderId)).as("时限落库").isEqualTo(4);
    }

    // ---------- 删除：软删 DELETED（仅作者、仅 DRAFT），全口径消失 ----------

    @Test
    void 软删草稿后公开与本人均不可见_非草稿不可删() {
        Session author = registerAndLogin("13800003004", "草稿用户3");
        String draftId = saveDraft(author, Map.of("description", "待删草稿"));

        JsonNode deleteDraft = deleteJson("/api/errand/orders/" + draftId, author);
        assertSuccess(deleteDraft, "删除草稿");

        assertThat(stateOf(draftId)).as("删除是软删（落 DELETED）").isEqualTo("DELETED");
        assertThat(rowCount(draftId)).as("软删不物理删行").isEqualTo(1);

        assertThat(orderIdsOf(getJson("/api/errand/orders?status=PUBLISHED", author)))
                .as("公开列表排除 DELETED").doesNotContain(draftId);
        assertThat(orderIdsOf(getJson("/api/errand/orders/mine?role=publish", author)))
                .as("我的列表排除 DELETED").doesNotContain(draftId);
        assertThat(getJson("/api/errand/orders/" + draftId, author).path("code").asText())
                .as("已删草稿详情不可见").isEqualTo("404");

        // 非草稿不可走草稿删除：直接发布单（PENDING_PAYMENT）删除被拒且状态不变
        String publishId = createDirectOrder(author, "正常发布");
        JsonNode deletePublished = deleteJson("/api/errand/orders/" + publishId, author);
        assertThat(deletePublished.path("code").asText())
                .as("仅 DRAFT 可达 DELETED：%s", deletePublished).isEqualTo("500");
        assertThat(stateOf(publishId)).isEqualTo("PENDING_PAYMENT");
    }

    // ---------- 提交：必填校验 / 同步初筛 / 通过后转待支付并同事务注册支付超时 ----------

    @Test
    void 不完整草稿提交失败停留草稿() {
        Session author = registerAndLogin("13800003005", "草稿用户4");
        String draftId = saveDraft(author, Map.of("description", "只写了描述"));

        JsonNode submit = postJson("/api/errand/orders/" + draftId + "/submit", null, author);
        assertThat(submit.path("code").asText()).as("缺必填项应拒绝提交：%s", submit).isEqualTo("500");
        assertThat(submit.path("info").asText()).contains("不完整");
        assertThat(stateOf(draftId)).as("校验失败停留草稿").isEqualTo("DRAFT");
        assertThat(payTimeoutRegistrations(draftId)).as("失败不注册支付超时").isZero();
    }

    @Test
    void 提交初筛命中停留草稿_改后提交转待支付并注册支付超时() {
        Session author = registerAndLogin("13800003006", "草稿用户5");
        String draftId = saveDraft(author, Map.of("description", "占位"));

        // 编辑成完整但含违禁词：草稿保存/编辑不做初筛，提交才做
        editCompleteDraft(author, draftId, "帮我代考");
        JsonNode hit = postJson("/api/errand/orders/" + draftId + "/submit", null, author);
        assertThat(hit.path("code").asText()).as("初筛命中应业务失败：%s", hit).isEqualTo("500");
        assertThat(hit.path("info").asText()).contains("违规词汇");
        assertThat(stateOf(draftId)).as("命中后停留草稿").isEqualTo("DRAFT");
        assertThat(payTimeoutRegistrations(draftId)).as("命中不注册支付超时").isZero();
        assertThat(getJson("/api/errand/orders/" + draftId, author).path("data").path("status").asText())
                .as("草稿原样保留可继续改").isEqualTo("DRAFT");

        // 改掉违禁词再提交：转待支付，且同事务注册 15 分钟支付超时
        editCompleteDraft(author, draftId, "帮我取快递");
        JsonNode retry = postJson("/api/errand/orders/" + draftId + "/submit", null, author);
        assertSuccess(retry, "改后重新提交");
        assertThat(stateOf(draftId)).as("提交后转待支付").isEqualTo("PENDING_PAYMENT");
        assertThat(payTimeoutRegistrations(draftId)).as("提交同事务注册支付超时").isEqualTo(1);
        assertThat(payTimeoutWithinFifteenMinutes(draftId)).as("到期时刻应为提交时刻 + 15 分钟").isEqualTo(1);
    }

    // ---------- 全链路：草稿 → 编辑 → 提交 → 支付 → 待审 ----------

    @Test
    void 草稿全链路_草稿到编辑到提交到支付到待审() {
        Session author = registerAndLogin("13800003007", "草稿用户6");
        String draftId = saveDraft(author, Map.of("description", "占位"));

        editCompleteDraft(author, draftId, "帮我取快递");
        assertSuccess(postJson("/api/errand/orders/" + draftId + "/submit", null, author), "提交草稿");
        assertThat(stateOf(draftId)).isEqualTo("PENDING_PAYMENT");

        assertSuccess(postJson("/api/errand/orders/" + draftId + "/pay", null, author), "支付");
        assertThat(stateOf(draftId)).as("支付成功即发布").isEqualTo("PUBLISHED");

        Integer frozenRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_detail WHERE order_id = ? AND type = 'FROZEN'",
                Integer.class, draftId);
        assertThat(frozenRows).as("支付冻结悬赏").isEqualTo(1);
        Integer paidMessages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_message WHERE destination = ?", Integer.class, Constants.ERRAND_PAID_TOPIC);
        assertThat(paidMessages).as("bs-errand-paid 消息随支付落 outbox").isEqualTo(1);
    }

    // ---------- 回归：早创建晚提交的草稿，支付钟自提交起算 ----------

    @Test
    void 早创建晚提交的草稿不被待支付兜底扫描误杀() {
        Session author = registerAndLogin("13800003009", "草稿用户7");
        String draftId = saveDraft(author, Map.of("description", "压了几天的草稿"));
        editCompleteDraft(author, draftId, "帮我取快递");
        // 草稿是几天前存的：创建时间回拨；支付钟只在提交后起算，兜底扫描不得按创建时刻判定
        jdbcTemplate.update(
                "UPDATE errand_order SET create_time = DATE_SUB(NOW(), INTERVAL 3 DAY) WHERE errand_order_id = ?", draftId);

        assertSuccess(postJson("/api/errand/orders/" + draftId + "/submit", null, author), "提交隔夜草稿");
        assertThat(stateOf(draftId)).isEqualTo("PENDING_PAYMENT");

        errandTimeoutScanJob.scan(null);
        assertThat(stateOf(draftId)).as("兜底扫描按进入待支付时刻（提交时刻）判定，刚提交不应被取消")
                .isEqualTo("PENDING_PAYMENT");
        assertThat(payTimeoutRegistrations(draftId)).isEqualTo(1);
    }

    // ---------- 回归：直接发布入口行为不变 ----------

    @Test
    void 直接发布入口仍落待支付并注册支付超时() {
        Session author = registerAndLogin("13800003008", "发布用户1");
        String orderId = createDirectOrder(author, "帮忙取个快递");

        assertThat(stateOf(orderId)).as("直接发布保持现状：落 PENDING_PAYMENT").isEqualTo("PENDING_PAYMENT");
        assertThat(payTimeoutRegistrations(orderId)).as("直接发布仍注册支付超时").isEqualTo(1);
    }

    // ---------- 工具 ----------

    private String saveDraft(Session author, Map<String, Object> body) {
        JsonNode save = postJson("/api/errand/orders/draft", body, author);
        assertSuccess(save, "保存跑腿草稿");
        String orderId = save.path("data").asText();
        assertUuidId(orderId);
        return orderId;
    }

    /** 编辑成一份"必填齐全"的草稿（描述可变，用于校验/初筛双路径） */
    private void editCompleteDraft(Session author, String orderId, String description) {
        JsonNode update = putJson("/api/errand/orders/" + orderId + "/draft", Map.ofEntries(
                Map.entry("type", "EXPRESS_PICKUP"),
                Map.entry("description", description),
                Map.entry("pickupLocation", "菜鸟驿站"),
                Map.entry("deliveryLocation", "6号楼楼下"),
                Map.entry("reward", 10),
                Map.entry("timeLimit", "HOURS_2")), author);
        assertSuccess(update, "编辑跑腿草稿");
    }

    private String createDirectOrder(Session author, String description) {
        JsonNode response = postJson("/api/errand/orders", Map.ofEntries(
                Map.entry("type", "EXPRESS_PICKUP"),
                Map.entry("description", description),
                Map.entry("pickupLocation", "菜鸟驿站"),
                Map.entry("deliveryLocation", "6号楼楼下"),
                Map.entry("reward", 10),
                Map.entry("timeLimit", "HOURS_2")), author);
        assertSuccess(response, "发布跑腿");
        String orderId = response.path("data").asText();
        assertUuidId(orderId);
        return orderId;
    }

    private String stateOf(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }

    private long rowCount(String orderId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM errand_order WHERE errand_order_id = ?", Long.class, orderId);
        return count == null ? 0 : count;
    }

    private String publisherIdOf(String orderId) {
        String publisherId = jdbcTemplate.queryForObject(
                "SELECT publisher_id FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
        return publisherId == null ? null : publisherId;
    }

    private Integer timeLimitHoursOf(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT time_limit_hours FROM errand_order WHERE errand_order_id = ?", Integer.class, orderId);
    }

    /** 该订单在 timeout_task 里的支付超时任务行数（草稿态应为 0，提交后为 1；同事务直插，见票 03） */
    private int payTimeoutRegistrations(String orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM timeout_task WHERE task_type = ? AND biz_id = ?",
                Integer.class, Constants.ERRAND_PAY_TIMEOUT, orderId);
        return count == null ? 0 : count;
    }

    /** 到期时刻落在 [now+14min, now+16min] 的注册行数（核对"提交时刻 + 15 分钟"） */
    private int payTimeoutWithinFifteenMinutes(String orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM timeout_task WHERE task_type = ? AND biz_id = ? "
                        + "AND action_time > DATE_ADD(NOW(), INTERVAL 14 MINUTE) "
                        + "AND action_time <= DATE_ADD(NOW(), INTERVAL 16 MINUTE)",
                Integer.class, Constants.ERRAND_PAY_TIMEOUT, orderId);
        return count == null ? 0 : count;
    }

    private List<String> orderIdsOf(JsonNode response) {
        return itemsOf(response).stream().map(node -> node.path("orderId").asText()).toList();
    }

    private List<JsonNode> itemsOf(JsonNode response) {
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false).toList();
    }
}
