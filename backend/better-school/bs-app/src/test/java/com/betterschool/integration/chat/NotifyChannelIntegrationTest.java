package com.betterschool.integration.chat;

import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeEnum;
import com.betterschool.domain.notification.model.valobj.NotificationTypeEnum;
import com.betterschool.domain.notification.service.INotificationService;
import com.betterschool.integration.support.PostApiTestBase;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知投递通道路由集成测试（不启动外部环境断言 WS，只断言 outbox/DB 外部可观察状态）：
 * <ul>
 *   <li>事件驱动：业务侧 {@code publish} 只写 bs-notify-event outbox（业务事务内一行），
 *       NotifyEventConsumer 消费时统一落 notification 表并**直接**按通道投递（不再有第二跳投递 outbox）</li>
 *   <li>CHAT 通道（CREDIT_CHANGE）：消费时同步经 chat 发送管线落系统消息
 *       （SYSTEM 类型，client_msg_id = NOTIFY:&lt;notificationId&gt;）；重复消费按 uk_room_client 收敛同一条</li>
 *   <li>BANNER 通道（互动四类）：消费时直接推 WS NOTIF 帧（尽力而为，本类只断言不再写投递 outbox；
 *       帧到达由 WsIntegrationTest 覆盖）</li>
 *   <li>NONE 通道（营销/公告）：只落通知中心，不投递</li>
 *   <li>消费幂等：同一事件重复消费（notificationId/dedupKey 双锚）不重复落库、不重复投递</li>
 *   <li>系统会话守卫：用户向系统助手会话发消息被 403 拒绝</li>
 * </ul>
 */
class NotifyChannelIntegrationTest extends PostApiTestBase {

    @Autowired
    private INotificationService notificationService;

    @Test
    @DisplayName("CHAT 通道：publish 只写 bs-notify-event，消费时同步落系统消息且重复消费幂等")
    void chatChannelDeliversSystemMessage() {
        Session user = registerAndLogin("13800000001", "收件人");
        String notificationId = UUID.randomUUID().toString();
        notificationService.publish(NotificationEventMessage.builder()
                .notificationId(notificationId)
                .userId(user.userId())
                .scope(NotificationScopeEnum.MARKET_PRODUCT.name())
                .type(NotificationTypeEnum.CREDIT_CHANGE.name())
                .title("信用分变动")
                .content("您的信用分发生变动")
                .refType("CREDIT")
                .refId("42")
                .dedupKey("CREDIT_CHANGE:ADMIN:42")
                .build());

        // 业务事务内只写通知事件 outbox，不直落 notification、不写任何投递 outbox
        List<String> eventPayloads = outboxPayloads(Constants.NOTIFY_EVENT_TOPIC);
        assertThat(eventPayloads).as("publish 应写一条通知事件 outbox").hasSize(1);
        assertThat(outboxPayloads("bs-notify-chat"))
                .as("投递实现在消费方内直接完成，不再有第二跳投递 outbox").isEmpty();

        // 消费（真实链路直调 trigger 消费方，同 WsIntegrationTest 口径）：落库 + 直接投递
        notifyEventConsumer.onMessage(eventPayloads.get(0));

        Integer notificationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'CREDIT_CHANGE'",
                Integer.class, user.userId());
        assertThat(notificationCount).as("通知应落库一条").isEqualTo(1);

        String roomId = jdbcTemplate.queryForObject(
                "SELECT room_id FROM user_pair_room WHERE user_min = ? OR user_max = ?", String.class,
                Constants.SYSTEM_USER_ID, Constants.SYSTEM_USER_ID);
        Integer messageCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message WHERE room_id = ?", Integer.class, roomId);
        assertThat(messageCount).as("系统消息应落一条（消费时同步投递）").isEqualTo(1);
        Map<String, Object> messageRow = jdbcTemplate.queryForMap(
                "SELECT msg_type, content, client_msg_id, from_user_id FROM message WHERE room_id = ?", roomId);
        assertThat(messageRow.get("msg_type")).isEqualTo("SYSTEM");
        assertThat((String) messageRow.get("client_msg_id"))
                .isEqualTo("NOTIFY:" + notificationId);
        JsonNode systemPayload = readTree((String) messageRow.get("content"));
        assertThat(systemPayload.path("title").asText()).isEqualTo("信用分变动");
        assertThat(systemPayload.path("refId").asText()).isEqualTo("42");

        // 重复消费同一事件（broker 重试/outbox 重发）：notificationId+dedupKey 双锚幂等，
        // 不重复落库；CHAT 投递再跑一次由 chat uk_room_client 收敛同一条
        notifyEventConsumer.onMessage(eventPayloads.get(0));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'CREDIT_CHANGE'",
                Integer.class, user.userId())).as("重复消费不重复落库").isEqualTo(1);
        Integer afterReplay = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message WHERE room_id = ?", Integer.class, roomId);
        assertThat(afterReplay).as("重复消费应幂等收敛").isEqualTo(1);
    }

    @Test
    @DisplayName("BANNER 通道：消费落库后直接推 WS，不再写 bs-notify-push outbox")
    void bannerChannelPushesDirectly() {
        Session user = registerAndLogin("13800000002", "被赞人");
        notificationService.publish(NotificationEventMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .userId(user.userId())
                .senderId("sender-1")
                .scope(NotificationScopeEnum.INTERACTION_LIKE.name())
                .type(NotificationTypeEnum.POST_LIKED.name())
                .title("收到的赞")
                .content("有人赞了你的帖子")
                .refType("POST")
                .refId("p1")
                .dedupKey("POST_LIKED:p1:sender-1")
                .build());

        consumeNotifyEvents();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'POST_LIKED'",
                Integer.class, user.userId())).as("通知应落库一条").isEqualTo(1);
        assertThat(outboxPayloads("bs-notify-push"))
                .as("横幅直接在消费方内推 WS，不再有第二跳投递 outbox").isEmpty();

        // 同一事件重投（notificationId 相同）：幂等命中不重复落库，也不重复弹横幅
        notifyEventConsumer.onMessage(outboxPayloads(Constants.NOTIFY_EVENT_TOPIC).get(0));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'POST_LIKED'",
                Integer.class, user.userId())).as("重复事件不重复落库").isEqualTo(1);
    }

    @Test
    @DisplayName("NONE 通道：LOTTERY_WON 消费只落通知中心，不产生任何投递 outbox")
    void noneChannelSkipsDelivery() {
        Session user = registerAndLogin("13800000003", "中奖人");
        notificationService.publish(NotificationEventMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .userId(user.userId())
                .scope(NotificationScopeEnum.BOTTOM_SYSTEM.name())
                .type(NotificationTypeEnum.LOTTERY_WON.name())
                .title("抽奖中奖")
                .content("恭喜获得一等奖")
                .refType("MARKETING")
                .refId("r1")
                .dedupKey("MARKETING:DRAWN:r1")
                .build());

        consumeNotifyEvents();

        List<String> notifyDestinations = jdbcTemplate.queryForList(
                "SELECT DISTINCT destination FROM mq_outbox WHERE destination LIKE 'bs-notify-%'", String.class);
        assertThat(notifyDestinations)
                .as("只有事件本身，没有投递 topic").containsOnly(Constants.NOTIFY_EVENT_TOPIC);
        Integer notificationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'LOTTERY_WON'",
                Integer.class, user.userId());
        assertThat(notificationCount).as("通知中心仍应可查").isEqualTo(1);
    }

    @Test
    @DisplayName("系统会话守卫：用户向系统助手会话发消息被拒绝")
    void userCannotReplyToSystemRoom() {
        Session user = registerAndLogin("13800000004", "普通用户");
        notificationService.publish(NotificationEventMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .userId(user.userId())
                .scope(NotificationScopeEnum.BOTTOM_SYSTEM.name())
                .type(NotificationTypeEnum.CREDIT_CHANGE.name())
                .title("信用分变动")
                .content("信用分 +2")
                .refType(null)
                .refId(null)
                .dedupKey("CREDIT_CHANGE:test:1")
                .build());
        consumeNotifyEvents();

        // 用户侧会话列表可见系统助手会话（roomId 为数值过渡期口径）
        JsonNode rooms = getJson("/api/chat/rooms?page=1&pageSize=10", user);
        assertThat(rooms.path("data").path("list").size()).as("系统助手会话应出现在列表").isEqualTo(1);
        long roomId = rooms.path("data").path("list").get(0).path("roomId").asLong();

        JsonNode reject = postJson("/api/chat/rooms/messages",
                Map.of("roomId", roomId, "clientMsgId", "user-try-1", "msgType", "TEXT", "content", "你好"), user);
        assertThat(reject.path("code").asText()).as("系统会话不可回复应被拒绝").isEqualTo("403");
    }

    private List<String> outboxPayloads(String destination) {
        return jdbcTemplate.queryForList(
                "SELECT payload FROM mq_outbox WHERE destination = ? ORDER BY id", String.class, destination);
    }
}
