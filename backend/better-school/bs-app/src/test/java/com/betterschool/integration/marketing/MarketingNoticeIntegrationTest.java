package com.betterschool.integration.marketing;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.trigger.listener.LotteryDrawnConsumer;
import com.betterschool.trigger.listener.PointsChangedConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 营销事件落通知（营销票 09）集成测试：单体作为消费者订阅两个营销 topic，
 * 事件 → 底部消息页 system 范围（scope = BOTTOM_SYSTEM）各落对应通知；重投（broker 重试/outbox 补发）
 * 由 dedup_key 幂等只落一条；载荷残缺静默丢弃不重投。
 * <p>
 * 事件体照营销服务 outbox 的真实形状构造（LotteryDrawnMessage / PointsChangedMessage），
 * 走消费者真实解析与处理路径。
 */
class MarketingNoticeIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private LotteryDrawnConsumer lotteryDrawnConsumer;
    @Autowired
    private PointsChangedConsumer pointsChangedConsumer;

    // ---------- 中奖事件：文案按 prizeType 呈现 ----------

    @Test
    void 积分档中奖事件落积分文案且重投只一条() {
        String userId = registerAndLogin("13800009003", "中奖用户").userId();
        // 票 13 起抽奖域的虚拟档口径是 VIRTUAL（旧值是 POINTS）
        Map<String, Object> event = Map.of("recordId", 201L, "userId", userId,
                "prizeId", 11L, "prizeType", "VIRTUAL", "pointsAmount", 100);

        lotteryDrawnConsumer.onMessage(json(event));
        lotteryDrawnConsumer.onMessage(json(event));

        assertThat(noticeCount(userId, "LOTTERY_WON", 201L))
                .as("ref_id 取 recordId，重投不重复").isEqualTo(1);
        String content = noticeContent(userId, "LOTTERY_WON", 201L);
        assertThat(content).contains("100").contains("积分");
        assertMarketingSystemNotice(userId, "LOTTERY_WON", "MARKETING:DRAWN:201");
    }

    @Test
    void 旧版积分档取值POINTS仍落积分文案() {
        String userId = registerAndLogin("13800009005", "旧版中奖用户").userId();
        lotteryDrawnConsumer.onMessage(json(Map.of("recordId", 203L, "userId", userId,
                "prizeId", 13L, "prizeType", "POINTS", "pointsAmount", 100)));

        String content = noticeContent(userId, "LOTTERY_WON", 203L);
        assertThat(content).as("在途旧消息不落成实物文案").contains("100").contains("积分");
    }

    @Test
    void 实物档中奖事件落领取指引文案() {
        String userId = registerAndLogin("13800009004", "实物中奖用户").userId();
        lotteryDrawnConsumer.onMessage(json(Map.of("recordId", 202L, "userId", userId,
                "prizeId", 12L, "prizeType", "PHYSICAL", "pointsAmount", 0)));

        String content = noticeContent(userId, "LOTTERY_WON", 202L);
        assertThat(content).contains("实物奖品").contains("我的奖品");
    }

    @Test
    void 谢谢参与事件不落通知() {
        String userId = registerAndLogin("13800009005", "谢谢参与用户").userId();
        lotteryDrawnConsumer.onMessage(json(Map.of("recordId", 203L, "userId", userId,
                "prizeId", 13L, "prizeType", "THANKS", "pointsAmount", 0)));
        assertThat(noticeCount(userId, "LOTTERY_WON", 203L)).isZero();
    }

    // ---------- 积分变动事件：变动量 + 余额，正负都可 ----------

    @Test
    void 积分入账与消费事件各落一条且文案带变动量与余额() {
        String userId = registerAndLogin("13800009006", "积分用户").userId();
        pointsChangedConsumer.onMessage(json(Map.of("userId", userId, "delta", 100,
                "balanceAfter", 350, "reasonType", "LOTTERY_WIN", "refId", 301L)));
        pointsChangedConsumer.onMessage(json(Map.of("userId", userId, "delta", -50,
                "balanceAfter", 300, "reasonType", "REDEEM_PRIZE", "refId", 401L)));

        assertThat(noticeContent(userId, "POINTS_CHANGED", 301L)).contains("+100").contains("350");
        assertThat(noticeContent(userId, "POINTS_CHANGED", 401L)).contains("-50").contains("300");
        assertMarketingSystemNotice(userId, "POINTS_CHANGED", "MARKETING:POINTS:LOTTERY_WIN:301");
        assertThat(noticeCount(userId, "POINTS_CHANGED", 301L)).isEqualTo(1);

        // 同一笔变动重投：dedup_key 幂等只一条
        pointsChangedConsumer.onMessage(json(Map.of("userId", userId, "delta", 100,
                "balanceAfter", 350, "reasonType", "LOTTERY_WIN", "refId", 301L)));
        assertThat(noticeCount(userId, "POINTS_CHANGED", 301L)).isEqualTo(1);
    }

    // ---------- 边界：残缺载荷静默丢弃 ----------

    @Test
    void 残缺载荷静默丢弃不落任何通知() {
        assertThatCode(() -> {
            lotteryDrawnConsumer.onMessage("{\"recordId\":201}");
            pointsChangedConsumer.onMessage("{\"userId\":9001,\"delta\":1}");
        }).doesNotThrowAnyException();

        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification", Integer.class);
        assertThat(rows).as("残缺载荷不落任何通知").isZero();
    }

    // ---------- 工具 ----------

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int noticeCount(String userId, String type, long refId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = ? AND ref_id = ?",
                Integer.class, userId, type, refId);
        return count == null ? 0 : count;
    }

    private String noticeContent(String userId, String type, long refId) {
        return jdbcTemplate.queryForObject(
                "SELECT content FROM notification WHERE user_id = ? AND type = ? AND ref_id = ?",
                String.class, userId, type, refId);
    }

    /** 营销通知三定值（票 07 §4）：type 营销枚举、ref_type = MARKETING、scope = BOTTOM_SYSTEM（不进市集消息页） */
    private void assertMarketingSystemNotice(String userId, String type, String dedupKey) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT scope, ref_type, dedup_key FROM notification WHERE user_id = ? AND type = ? LIMIT 1",
                userId, type);
        assertThat(row.get("scope")).as("落底部消息页 system tab").isEqualTo("BOTTOM_SYSTEM");
        assertThat(row.get("ref_type")).isEqualTo("MARKETING");
        assertThat(row.get("dedup_key")).isEqualTo(dedupKey);
    }
}
