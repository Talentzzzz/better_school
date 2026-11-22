package com.betterschool.integration.raffle;

import com.betterschool.integration.support.RaffleApiTestBase;
import com.betterschool.integration.support.TestingEnvironment;
import com.betterschool.infrastructure.dao.outbox.IOutboxDao;
import com.betterschool.types.common.Constants;
import com.betterschool.types.enums.OutboxStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 发货闭环生产侧用例（票 14 验收 2 生产侧 + 3）：
 * 抽奖成功后 outbox 有且仅有一行、destination / 载荷正确、与业务写同事务（后续异常 → 一起回滚）；
 * 以及 state=0 超时行被重发、重试耗尽置 state=2。
 * <p>
 * 测试栈没有 broker（票 05 交接档 §5）：发送必然失败，这恰好把「落库 → 重发 → 死信」三段全暴露出来。
 * <p>
 * 票 02 起另含共用件用例：`uk_msg_id` 撞键、`delay_ms` 随行往返、以及扫描重发前的回读。
 * 最后一项是本类唯一碰 DAO 的断言——`delay_ms → delayMs` 的映射没有外部可观察面
 * （无 broker 时「定时发」与「直发」都只表现为发送失败），只能看回读值。
 */
public class RaffleShippingOutboxIntegrationTest extends RaffleApiTestBase {

    private static final String USER = "user-3101";
    private static final String OTHER_USER = "user-3102";
    private static final String TOKEN = "b6d4f0a0-2222-4bbb-8ccc-000000000002";

    @Autowired
    private IOutboxDao outboxDao;

    /** 把概率变成确定性：全部 normal 档归零后只留目标档 1.0 */
    private void onlyAwardRate(String awardId) {
        jdbcTemplate.update("UPDATE draw_strategy_award SET award_rate = 0 WHERE type = 'normal'");
        jdbcTemplate.update("UPDATE draw_strategy_award SET award_rate = 1 WHERE award_id = ?", awardId);
        raffleWarmupService.warmupByStrategyId(STRATEGY_ID);
    }

    @Test
    @DisplayName("中奖落库：outbox 有且仅有一行、destination = bs-lottery-drawn、载荷与 draw_record 逐字段一致")
    void draw_writesSingleOutboxEventInSameTransaction() {
        prepareDefaultScenario();
        onlyAwardRate(AWARD_POINTS);
        giveChances(USER, 1);

        JsonNode response = draw(USER, TOKEN);
        TestingEnvironment.assertSuccess(response, "单抽");
        String recordId = response.path("data").path("recordId").asText();

        assertThat(outboxCount(Constants.LOTTERY_DRAWN_TOPIC)).as("有且仅有一行中奖事件").isEqualTo(1);
        JsonNode event = onlyOutboxEvent(Constants.LOTTERY_DRAWN_TOPIC);
        Map<String, Object> record = lastDrawRecordOf(USER);
        assertThat(event.path("recordId").asText()).isEqualTo(recordId);
        assertThat(event.path("recordId").asText())
                .as("载荷 recordId 与 draw_record 一致（uuid-biz-id 票 07 起 record_id/award_id 是 UUID 字符串）")
                .isEqualTo((String) record.get("record_id"));
        assertThat(event.path("userId").asText()).isEqualTo(USER);
        assertThat(event.path("prizeId").asText())
                .isEqualTo((String) record.get("award_id"));
        assertThat(event.path("prizeType").asText()).isEqualTo("VIRTUAL");
        assertThat(event.path("pointsAmount").asInt()).as("积分档面额取自台账").isEqualTo(20);

        Map<String, Object> row = outboxRow(Constants.LOTTERY_DRAWN_TOPIC);
        assertThat(row.get("msg_id")).as("幂等键 msg_id 已落").isNotNull();
        assertThat(((Number) row.get("state")).intValue())
                .as("无 broker：发送失败，要么仍 PENDING、要么已被标记重试态")
                .isIn(OutboxStatus.PENDING.getCode(), OutboxStatus.SENT.getCode());
    }

    @Test
    @DisplayName("谢谢参与不落事件；实物档也落事件（发货消费者要它推进状态）")
    void draw_thanksWritesNoEvent_physicalWritesEvent() {
        prepareDefaultScenario();
        onlyAwardRate(AWARD_THANKS);
        giveChances(USER, 2);
        TestingEnvironment.assertSuccess(draw(USER, TOKEN), "谢谢参与单抽");
        assertThat(outboxCount(Constants.LOTTERY_DRAWN_TOPIC)).as("谢谢参与不发事件").isZero();

        // 换 token 再抽实物（同一用户同一活动）
        jdbcTemplate.update("UPDATE draw_strategy_award SET award_rate = 0 WHERE type = 'normal'");
        jdbcTemplate.update("UPDATE draw_strategy_award SET award_rate = 1 WHERE award_id = ?", AWARD_BAG);
        raffleWarmupService.warmupByStrategyId(STRATEGY_ID);
        JsonNode physical = draw(USER, TOKEN + "-bag");
        TestingEnvironment.assertSuccess(physical, "实物单抽");

        assertThat(outboxCount(Constants.LOTTERY_DRAWN_TOPIC)).as("实物档落事件").isEqualTo(1);
        JsonNode event = onlyOutboxEvent(Constants.LOTTERY_DRAWN_TOPIC);
        assertThat(event.path("prizeType").asText()).isEqualTo("PHYSICAL");
        assertThat(event.path("pointsAmount").isNull()).as("非积分档面额为空").isTrue();
    }

    @Test
    @DisplayName("同事务：业务事务后半段异常 → draw_record、明细、outbox 事件一起回滚（一处都不落）")
    void outboxEvent_rollsBackWithBusinessTransaction() {
        String probeRecordId = "777001";

        outboxTransactionProbe.insertThenCommit(OTHER_USER, probeRecordId);
        assertThat(drawRecordCountOf(OTHER_USER)).as("正常路径：记录已提交").isEqualTo(1);
        assertThat(outboxCount(Constants.LOTTERY_DRAWN_TOPIC)).as("正常路径：事件已提交").isEqualTo(1);

        String failedRecordId = "777002";
        assertThat(catchFailure(() -> outboxTransactionProbe.insertThenFail(OTHER_USER, failedRecordId)))
                .as("探针应把异常抛出来").isTrue();
        assertThat(drawRecordCountOf(OTHER_USER)).as("回滚后没有第二条记录").isEqualTo(1);
        assertThat(outboxCount(Constants.LOTTERY_DRAWN_TOPIC)).as("回滚后没有第二条事件").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM draw_record_detail WHERE record_id = ?",
                Integer.class, failedRecordId)).as("回滚后没有明细").isZero();
    }

    private boolean catchFailure(Runnable action) {
        try {
            action.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    @Test
    @DisplayName("重发：state=0 且 update_time 超时的行被扫描重发（重试计数 +1），未超时的行不动")
    void scanPending_resendsOnlyTimedOutRows() {        prepareDefaultScenario();
        onlyAwardRate(AWARD_POINTS);
        giveChances(USER, 2);
        TestingEnvironment.assertSuccess(draw(USER, TOKEN), "第一抽");
        TestingEnvironment.assertSuccess(draw(USER, TOKEN + "-2"), "第二抽");

        Long timedOutId = jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM outbox_message WHERE destination = ?", Long.class, Constants.LOTTERY_DRAWN_TOPIC);
        Long freshId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM outbox_message WHERE destination = ?", Long.class, Constants.LOTTERY_DRAWN_TOPIC);
        // 等首次异步发送的失败回调落定（无 broker 必然失败）：失败标记走另一个线程，
        // 落定后 retry_count 各 +1（标记线程也会刷 update_time，不能用「时间变新」判断）
        awaitTrue("两条消息的首次发送失败标记落定", Duration.ofSeconds(20),
                () -> retryCountOf(timedOutId) >= 1 && retryCountOf(freshId) >= 1);
        int timedOutRetryBefore = retryCountOf(timedOutId);
        int freshRetryBefore = retryCountOf(freshId);

        // 同一时刻起跑：一条推回 10 分钟前（远超 test profile 的 2s 超时窗），一条保持「刚提交」
        jdbcTemplate.update("UPDATE outbox_message SET state = 0, update_time = DATE_SUB(NOW(), INTERVAL 10 MINUTE)"
                + " WHERE id = ?", timedOutId);
        jdbcTemplate.update("UPDATE outbox_message SET state = 0, update_time = NOW() WHERE id = ?", freshId);

        outboxMessageSender.scanPending("");

        awaitTrue("超时行被重发（retry_count 增加）", Duration.ofSeconds(10),
                () -> retryCountOf(timedOutId) == timedOutRetryBefore + 1);
        // 未超时的行不该被扫描碰到：给它一点时间暴露「被扫到」的迹象
        sleepQuietly(500);
        assertThat(retryCountOf(freshId)).as("未超时的行不被扫描碰到").isEqualTo(freshRetryBefore);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("重试耗尽：最后一次发送失败把该行置 state=2（死信），不再重发")
    void scanPending_marksDeadAfterRetryExhausted() {
        prepareDefaultScenario();
        onlyAwardRate(AWARD_POINTS);
        giveChances(USER, 1);
        TestingEnvironment.assertSuccess(draw(USER, TOKEN), "单抽");

        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM outbox_message WHERE destination = ?", Long.class, Constants.LOTTERY_DRAWN_TOPIC);
        // 预置「已重试 4 次」（阈值 5）：下一次失败即耗尽
        jdbcTemplate.update(
                "UPDATE outbox_message SET retry_count = 4, update_time = DATE_SUB(NOW(), INTERVAL 10 MINUTE)"
                        + " WHERE id = ?", id);

        outboxMessageSender.scanPending("");

        awaitTrue("重试耗尽后置 state=2", Duration.ofSeconds(10),
                () -> outboxState(id) == OutboxStatus.DEAD.getCode());
        assertThat(outboxState(id)).as("死信态不参与后续扫描").isEqualTo(OutboxStatus.DEAD.getCode());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_message WHERE destination = ? AND state = 0",
                Integer.class, Constants.LOTTERY_DRAWN_TOPIC)).as("不再有待发送行").isZero();
    }

    @Test
    @DisplayName("中奖事件延迟级别为 0（库存合并消息已随票 07 退休，确认不再产生 delay_level=1 的行）")
    void delayLevel_raffleEventStaysZero() {
        prepareDefaultScenario();
        onlyAwardRate(AWARD_POINTS);
        giveChances(USER, 1);
        TestingEnvironment.assertSuccess(draw(USER, TOKEN), "单抽");

        assertThat(delayLevelOf(Constants.LOTTERY_DRAWN_TOPIC)).as("中奖事件仍是 0").isZero();
    }

    private int retryCountOf(Long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT retry_count FROM outbox_message WHERE id = ?", Integer.class, id);
        return count == null ? -1 : count;
    }

    @Test
    @DisplayName("uk_msg_id 幂等：同一 msg_id 的第二行撞唯一键，表里仍只有一行（delay_ms 随行往返）")
    void sameMsgId_isAbsorbedByUniqueKey() {
        // save() 每次生成新的 msg_id，撞键只可能来自「同一行被重复插入」，所以这里直接落 SQL
        String msgId = "dup-" + System.nanoTime();
        insertPendingRow(msgId);
        assertThatThrownBy(() -> insertPendingRow(msgId))
                .as("同一 msg_id 再来一行：唯一键挡在库里，不是应用层判断")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_message WHERE msg_id = ?", Integer.class, msgId))
                .as("撞键不产生第二行").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delay_ms FROM outbox_message WHERE msg_id = ?", Integer.class, msgId))
                .as("毫秒级延迟随行持久化").isEqualTo(2000);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delay_level FROM outbox_message WHERE msg_id = ?", Integer.class, msgId))
                .as("与 delay_ms 互斥：级别落 0").isZero();

        // 扫描重发读的就是这一列：delay_ms → delayMs 的映射没有别的可观察面
        // （无 broker 时定时与直发都表现为发送失败），只能落到 DAO 上看回读值
        assertThat(outboxDao.selectPendingList(0, 100))
                .anySatisfy(row -> {
                    assertThat(row.getMsgId()).isEqualTo(msgId);
                    assertThat(row.getDelayMs()).as("回读的毫秒延迟不能丢，否则重发会变成定时重发")
                            .isEqualTo(2000);
                });
    }

    private void insertPendingRow(String msgId) {
        jdbcTemplate.update("INSERT INTO outbox_message"
                        + " (msg_id, destination, payload, delay_level, delay_ms, state, retry_count,"
                        + " create_time, update_time)"
                        + " VALUES (?, ?, ?, 0, 2000, ?, 0, NOW(), NOW())",
                msgId, "uk-probe-destination",
                "{\"probe\":\"uk_msg_id 唯一键夹具，载荷形状无所谓\"}",
                OutboxStatus.PENDING.getCode());
    }

    private int delayLevelOf(String destination) {
        Integer level = jdbcTemplate.queryForObject(
                "SELECT delay_level FROM outbox_message WHERE destination = ? ORDER BY id DESC LIMIT 1",
                Integer.class, destination);
        return level == null ? -1 : level;
    }
}
