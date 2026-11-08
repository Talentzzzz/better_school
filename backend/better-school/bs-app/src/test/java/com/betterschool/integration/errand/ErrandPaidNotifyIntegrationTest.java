package com.betterschool.integration.errand;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.trigger.listener.ErrandPaidConsumer;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付消息消费者集成测试：
 * 消费 bs-errand-paid → 作者收到一条"支付成功、订单已可见"通知（重复消费 dedup_key 幂等只一条）
 */
class ErrandPaidNotifyIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private ErrandPaidConsumer errandPaidConsumer;

    // ---------- 支付消费者：作者"支付成功"通知 ----------

    @Test
    void 消费支付消息作者收到支付成功通知且重复消费只一条() {
        Session author = registerAndLogin("13800004001", "发单作者");
        String orderId = createPaidOrder(author);

        List<String> payloads = jdbcTemplate.queryForList(
                "SELECT payload FROM outbox_message WHERE destination = ? AND state = 0",
                String.class, Constants.ERRAND_PAID_TOPIC);
        assertThat(payloads).as("支付应落 bs-errand-paid outbox").hasSize(1);
        assertThat(readTree(payloads.get(0)).path("orderId").asText()).as("载荷 orderId 为 UUID 字符串（uuid-biz-id 票 05）").isEqualTo(orderId);

        // 消费两次（broker 重投场景）：dedup_key 幂等，作者只一条通知
        errandPaidConsumer.onMessage(payloads.get(0));
        errandPaidConsumer.onMessage(payloads.get(0));

        Integer noticeRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'ORDER_EVENT' "
                        + "AND content LIKE '%支付成功%' AND ref_id = ?",
                Integer.class, author.userId(), orderId);
        assertThat(noticeRows).as("重复消费只落一条支付成功通知").isEqualTo(1);
    }

    @Test
    void 消费不存在的订单id静默忽略() {
        errandPaidConsumer.onMessage("{\"orderId\":\"" + java.util.UUID.randomUUID() + "\"}");
        Integer noticeRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification", Integer.class);
        assertThat(noticeRows).as("订单不存在不应落任何通知").isZero();
    }

    // ---------- 工具 ----------

    private String createPaidOrder(Session author) {
        JsonNode createResponse = postJson("/api/errand/orders", Map.ofEntries(
                Map.entry("type", "EXPRESS_PICKUP"),
                Map.entry("description", "帮忙取个快递"),
                Map.entry("pickupLocation", "菜鸟驿站"),
                Map.entry("deliveryLocation", "6号楼楼下"),
                Map.entry("reward", 10),
                Map.entry("timeLimit", "HOURS_2")), author);
        assertSuccess(createResponse, "发布跑腿");
        String orderId = createResponse.path("data").asText();
        assertUuidId(orderId);
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付");
        return orderId;
    }
}
