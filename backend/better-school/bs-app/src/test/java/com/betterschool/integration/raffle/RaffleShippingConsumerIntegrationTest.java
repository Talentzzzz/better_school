package com.betterschool.integration.raffle;

import com.betterschool.integration.support.RaffleApiTestBase;
import com.betterschool.integration.support.TestingEnvironment;
import com.betterschool.types.inventory.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发货闭环消费侧用例（票 14 验收 2 消费侧）：直喂 {@code bs-lottery-drawn} 消息后
 * {@code draw_record.state} 与 {@code draw_record_detail} 的推进，以及重复投递不重复推进。
 * <p>
 * 测试栈无 broker，消费侧当普通 Bean 直接调用（与 {@code PointsApiTestBase.publishLotteryDrawn} 同手法）；
 * 「生产侧落 outbox」与「消费侧入口」各断言一半拼成端到端（票 05 交接档 §2 的接缝手法）。
 * 状态机全图见 {@code IRaffleShippingService}：虚拟 / 次数档 EXCHANGE+COMPLETE 一步到已完成；
 * 实物档 SHIP 到已发货并登记库存兑换确认（票 07 起确认返回即转移已提交：
 * {@code waiting -= c, issued += c} 与状态推进同一次调用内完成，没有第二步）。
 * 中奖事件与 outbox 保留——确认仍由消费者驱动。
 */
public class RaffleShippingConsumerIntegrationTest extends RaffleApiTestBase {

    private static final String USER = "user-3201";
    private static final String OTHER_USER = "user-3202";

    private void onlyAwardRate(String awardId) {
        jdbcTemplate.update("UPDATE draw_strategy_award SET award_rate = 0 WHERE type = 'normal'");
        jdbcTemplate.update("UPDATE draw_strategy_award SET award_rate = 1 WHERE award_id = ?", awardId);
        raffleWarmupService.warmupByStrategyId(STRATEGY_ID);
    }

    /** 抽一注并返回 recordId */
    private String drawAndGetRecordId(String userId, String token) {
        JsonNode response = draw(userId, token);
        TestingEnvironment.assertSuccess(response, "单抽");
        return response.path("data").path("recordId").asText();
    }

    /** 该记录的实物兑换确认行数（{@code type = 2}）：行本身是唯一事实 */
    private int claimRowCount(String recordId) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM physical_marketing_quota_detail
                        WHERE inv_id = ? AND type = ? AND biz_id = ?
                        """,
                Integer.class, QUOTA_INV_ID, Constants.DETAIL_TYPE_CLAIM, recordId);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("虚拟档：消费中奖事件 → state=已完成，明细 EXCHANGE + COMPLETE 各一条")
    void ship_virtualPrize_completesWithTwoActions() {
        prepareDefaultScenario();
        onlyAwardRate(AWARD_POINTS);
        giveChances(USER, 1);
        String recordId = drawAndGetRecordId(USER, "token-virtual");
        assertThat(stateOf(recordId)).as("抽中时停在待兑换").isEqualTo("待兑换");

        publishLotteryDrawn(recordId);

        assertThat(stateOf(recordId)).as("虚拟档消费即完成").isEqualTo("已完成");
        assertThat(detailCountOf(recordId, "DRAW")).isEqualTo(1);
        assertThat(detailCountOf(recordId, "EXCHANGE")).as("兑换动作明细一条").isEqualTo(1);
        assertThat(detailCountOf(recordId, "COMPLETE")).as("完成动作明细一条").isEqualTo(1);
        assertThat(detailCountOf(recordId, "SHIP")).as("虚拟档不走发货").isZero();
    }

    @Test
    @DisplayName("虚拟档重复投递：不重复推进、不重复落明细、状态不回头")
    void ship_virtualPrize_replayIsIdempotent() {
        prepareDefaultScenario();
        onlyAwardRate(AWARD_POINTS);
        giveChances(USER, 1);
        String recordId = drawAndGetRecordId(USER, "token-virtual-replay");

        publishLotteryDrawn(recordId);
        long detailCountAfterFirst = drawDetailCountOf(recordId);
        publishLotteryDrawn(recordId);
        publishLotteryDrawn(recordId);

        assertThat(stateOf(recordId)).isEqualTo("已完成");
        assertThat(drawDetailCountOf(recordId)).as("重复投递不重复落明细").isEqualTo(detailCountAfterFirst);
        assertThat(detailCountOf(recordId, "EXCHANGE")).isEqualTo(1);
        assertThat(detailCountOf(recordId, "COMPLETE")).isEqualTo(1);
    }

    @Test
    @DisplayName("实物档：消费中奖事件 → 已发货 + 确认返回即转移（waiting→issued 已提交，无第二步）")
    void ship_physicalPrize_confirmTransfersImmediately() {
        prepareDefaultScenario();
        onlyAwardRate(AWARD_BAG);
        giveChances(USER, 1);
        String recordId = drawAndGetRecordId(USER, "token-physical");

        // 预扣返回即台账已动（票 06）：waiting 已增，无需再等合并
        assertThat(quotaOf(QUOTA_INV_ID).get("waiting_qty")).as("预扣已翻进 waiting").isEqualTo(1L);

        publishLotteryDrawn(recordId);

        assertThat(stateOf(recordId)).as("实物档消费即已发货").isEqualTo("已发货");
        assertThat(detailCountOf(recordId, "SHIP")).as("发货动作明细一条").isEqualTo(1);
        assertThat(detailCountOf(recordId, "COMPLETE")).as("收货前还没有完成动作").isZero();
        assertThat(claimRowCount(recordId)).as("确认落一行 type=2 明细").isEqualTo(1);

        // B6：确认返回时转移已提交——waiting -= 1 / issued += 1，没有第二步
        var afterConfirm = quotaOf(QUOTA_INV_ID);
        assertThat(afterConfirm.get("waiting_qty")).as("确认返回即 waiting 转走").isEqualTo(0L);
        assertThat(afterConfirm.get("issued_qty")).as("确认返回即计入 issued").isEqualTo(1L);
        assertThat(afterConfirm.get("surplus_qty")).as("台账总量不变（9 份剩余）").isEqualTo(9L);
        assertThat(outboxCountOtherThanLotteryDrawn()).as("确认不再发合并消息（没有第二步）").isZero();

        // 确认收货：已发货 → 已完成
        JsonNode received = confirmReceived(USER, recordId);
        TestingEnvironment.assertSuccess(received, "确认收货");
        assertThat(received.path("data").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(stateOf(recordId)).isEqualTo("已完成");
        assertThat(detailCountOf(recordId, "COMPLETE")).isEqualTo(1);
    }

    @Test
    @DisplayName("实物档重复投递：撞 uk_biz_type 按成功回放——只一行确认明细、不重复转移")
    void ship_physicalPrize_replayDoesNotConfirmTwice() {
        prepareDefaultScenario();
        onlyAwardRate(AWARD_BAG);
        giveChances(USER, 1);
        String recordId = drawAndGetRecordId(USER, "token-physical-replay");

        publishLotteryDrawn(recordId);
        assertThat(quotaOf(QUOTA_INV_ID).get("issued_qty")).as("首次确认即转移").isEqualTo(1L);

        // 再铺一份悬挂预扣（模拟另一笔未确认的预扣），重投不能把它也确认掉
        jdbcTemplate.update("UPDATE physical_marketing_quota SET waiting_qty = 1 WHERE inv_id = ?", QUOTA_INV_ID);
        publishLotteryDrawn(recordId);
        publishLotteryDrawn(recordId);

        assertThat(stateOf(recordId)).isEqualTo("已发货");
        assertThat(detailCountOf(recordId, "SHIP")).as("发货明细仍只有一条").isEqualTo(1);
        assertThat(claimRowCount(recordId)).as("确认明细仍只有一行（撞键回放）").isEqualTo(1);
        assertThat(quotaOf(QUOTA_INV_ID).get("issued_qty")).as("issued 不再增长").isEqualTo(1L);
        assertThat(quotaOf(QUOTA_INV_ID).get("waiting_qty")).as("那份悬挂预扣没被这次重投确认").isEqualTo(1L);
    }

    @Test
    @DisplayName("确认收货：非本人 / 状态不是已发货 → 4314；重复确认幂等返回 COMPLETED")
    void confirmReceived_guardsOwnershipAndState() {
        prepareDefaultScenario();
        onlyAwardRate(AWARD_BAG);
        giveChances(USER, 1);
        String recordId = drawAndGetRecordId(USER, "token-receive-guard");

        // 还在待兑换：不可确认收货
        TestingEnvironment.assertFailure(confirmReceived(USER, recordId), "4314", "该奖品不可确认收货");
        // 不是本人的记录：同码，不泄漏「存在但不是你的」
        TestingEnvironment.assertFailure(confirmReceived(OTHER_USER, recordId), "4314", "该奖品不可确认收货");

        publishLotteryDrawn(recordId);
        TestingEnvironment.assertSuccess(confirmReceived(USER, recordId), "首次确认收货");
        // 重复确认：幂等，仍是 COMPLETED
        JsonNode again = confirmReceived(USER, recordId);
        TestingEnvironment.assertSuccess(again, "重复确认收货");
        assertThat(again.path("data").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(detailCountOf(recordId, "COMPLETE")).as("完成明细只落一条").isEqualTo(1);
    }

    @Test
    @DisplayName("记录不存在的中奖事件：消费不抛异常、不落任何明细（重投也不会成功）")
    void ship_unknownRecord_isSkipped() {
        prepareDefaultScenario();
        publishLotteryDrawnRaw("999999", USER, "VIRTUAL", 20);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM draw_record_detail WHERE record_id = ?",
                Integer.class, "999999")).isZero();
    }
}
