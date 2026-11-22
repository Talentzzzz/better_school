package com.betterschool.integration.chat;

import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;
import com.betterschool.domain.notification.service.INotificationService;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.trigger.listener.NotifyChatConsumer;
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
 *       NotifyEventConsumer 消费时统一落 notification 表并按通道写投递 outbox</li>
 *   <li>CHAT 通道（ORDER_EVENT/CREDIT_CHANGE）：消费落库写 bs-notify-chat outbox；再经
 *       chat 发送管线落系统消息（SYSTEM 类型，client_msg_id = NOTIFY:&lt;notificationId&gt;）；
 *       重复消费按 uk_room_client 收敛同一条</li>
 *   <li>BANNER 通道（互动四类）：消费落库写 bs-notify-push outbox（横幅加速路径）</li>
 *   <li>NONE 通道（营销/公告）：只落通知中心，不写任何通知投递 outbox</li>
 *   <li>消费幂等：同一事件重复消费（notificationId/dedupKey 双锚）不重复落库、不重复投递</li>
 *   <li>系统会话守卫：用户向系统助手会话发消息被 403 拒绝</li>
 * </ul>
 */
class NotifyChannelIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private INotificationService notificationService;

    @Autowired
    private NotifyChatConsumer notifyChatConsumer;

    @Test
    @DisplayName("CHAT 通道：publish 只写 bs-notify-event outbox，消费落库写 bs-notify-chat，再落系统消息且重复消费幂等")
    void chatChannelDeliversSystemMessage() {
        Session user = registerAndLogin("13800000001", "收件人");
        notificationService.publish(NotificationEventMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .userId(user.userId())
                .scope(NotificationScopeVO.MARKET_ERRAND.name())
                .type(NotificationTypeVO.ORDER_EVENT.name())
                .title("订单动态")
                .content("您的跑腿订单已支付成功")
                .refType("ERRAND")
                .refId("42")
                .dedupKey("ORDER_EVENT:ERRAND_PAID:42")
                .build());

        // 业务事务内只写通知事件 outbox，不直落 notification、不直写通道 outbox
        List<String> eventPayloads = outboxPayloads(Constants.NOTIFY_EVENT_TOPIC);
        assertThat(eventPayloads).as("publish 应写一条通知事件 outbox").hasSize(1);
        assertThat(outboxPayloads(Constants.NOTIFY_CHAT_TOPIC))
                .as("落库前不应有 CHAT 通道 outbox（落库时机=事件消费时）").isEmpty();

        // 消费（真实链路直调 trigger 消费方，同 WsIntegrationTest 口径）：落库 + 通道路由
        notifyEventConsumer.onMessage(eventPayloads.get(0));

        Integer notificationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'ORDER_EVENT'",
                Integer.class, user.userId());
        assertThat(notificationCount).as("通知应落库一条").isEqualTo(1);

        List<String> chatPayloads = outboxPayloads(Constants.NOTIFY_CHAT_TOPIC);
        assertThat(chatPayloads).as("落库成功后应写一条 CHAT 通道投递事件").hasSize(1);
        JsonNode payload = readTree(chatPayloads.get(0));
        assertThat(payload.path("userId").asText()).isEqualTo(user.userId());
        assertThat(payload.path("type").asText()).isEqualTo("ORDER_EVENT");

        notifyChatConsumer.onMessage(chatPayloads.get(0));

        String roomId = jdbcTemplate.queryForObject(
                "SELECT room_id FROM user_pair_room WHERE user_min = ? OR user_max = ?", String.class,
                Constants.SYSTEM_USER_ID, Constants.SYSTEM_USER_ID);
        Integer messageCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message WHERE room_id = ?", Integer.class, roomId);
        assertThat(messageCount).as("系统消息应落一条").isEqualTo(1);
        Map<String, Object> messageRow = jdbcTemplate.queryForMap(
                "SELECT msg_type, content, client_msg_id, from_user_id FROM message WHERE room_id = ?", roomId);
        assertThat(messageRow.get("msg_type")).isEqualTo("SYSTEM");
        assertThat((String) messageRow.get("client_msg_id"))
                .startsWith("NOTIFY:").endsWith(payload.path("notificationId").asText());
        JsonNode systemPayload = readTree((String) messageRow.get("content"));
        assertThat(systemPayload.path("title").asText()).isEqualTo("订单动态");
        assertThat(systemPayload.path("refId").asText()).isEqualTo("42");

        // 重复消费同一事件（broker 重试/outbox 重发）：notificationId+dedupKey 双锚幂等，
        // 不重复落库、不重复写投递 outbox；chat 侧 uk_room_client 收敛同一条
        notifyEventConsumer.onMessage(eventPayloads.get(0));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'ORDER_EVENT'",
                Integer.class, user.userId())).as("重复消费不重复落库").isEqualTo(1);
        assertThat(outboxPayloads(Constants.NOTIFY_CHAT_TOPIC)).as("重复消费不重复投递").hasSize(1);
        notifyChatConsumer.onMessage(outboxPayloads(Constants.NOTIFY_CHAT_TOPIC).get(0));
        Integer afterReplay = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message WHERE room_id = ?", Integer.class, roomId);
        assertThat(afterReplay).as("重复消费应幂等收敛").isEqualTo(1);
    }

    @Test
    @DisplayName("BANNER 通道：消费落库写 bs-notify-push outbox（横幅加速路径），重复事件不重复投")
    void bannerChannelWritesPushEvent() {
        Session user = registerAndLogin("13800000002", "被赞人");
        notificationService.publish(NotificationEventMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .userId(user.userId())
                .senderId("sender-1")
                .scope(NotificationScopeVO.INTERACTION_LIKE.name())
                .type(NotificationTypeVO.POST_LIKED.name())
                .title("收到的赞")
                .content("有人赞了你的帖子")
                .refType("POST")
                .refId("p1")
                .dedupKey("POST_LIKED:p1:sender-1")
                .build());

        consumeNotifyEvents();

        List<String> pushPayloads = outboxPayloads(Constants.NOTIFY_PUSH_TOPIC);
        assertThat(pushPayloads).as("消费落库后应写一条 BANNER 通道推送事件").hasSize(1);
        JsonNode payload = readTree(pushPayloads.get(0));
        assertThat(payload.path("scope").asText()).isEqualTo("INTERACTION_LIKE");
        assertThat(payload.path("seq").asLong()).isEqualTo(1L);
        assertThat(payload.path("type").asText()).isEqualTo("POST_LIKED");

        // 同一事件重投（notificationId 相同）：幂等命中不重复落库 → 无 seq 不重复投递
        notifyEventConsumer.onMessage(outboxPayloads(Constants.NOTIFY_EVENT_TOPIC).get(0));
        assertThat(outboxPayloads(Constants.NOTIFY_PUSH_TOPIC)).as("幂等命中不重复写推送事件").hasSize(1);
    }

    @Test
    @DisplayName("NONE 通道：LOTTERY_WON 消费只落通知中心，不写任何通知投递 outbox")
    void noneChannelSkipsDelivery() {
        Session user = registerAndLogin("13800000003", "中奖人");
        notificationService.publish(NotificationEventMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .userId(user.userId())
                .scope(NotificationScopeVO.BOTTOM_SYSTEM.name())
                .type(NotificationTypeVO.LOTTERY_WON.name())
                .title("抽奖中奖")
                .content("恭喜获得一等奖")
                .refType("MARKETING")
                .refId("r1")
                .dedupKey("MARKETING:DRAWN:r1")
                .build());

        consumeNotifyEvents();

        assertThat(outboxPayloads(Constants.NOTIFY_CHAT_TOPIC)).isEmpty();
        assertThat(outboxPayloads(Constants.NOTIFY_PUSH_TOPIC)).isEmpty();
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
                .scope(NotificationScopeVO.BOTTOM_SYSTEM.name())
                .type(NotificationTypeVO.CREDIT_CHANGE.name())
                .title("信用分变动")
                .content("信用分 +2")
                .refType(null)
                .refId(null)
                .dedupKey("CREDIT_CHANGE:test:1")
                .build());
        consumeNotifyEvents();
        notifyChatConsumer.onMessage(outboxPayloads(Constants.NOTIFY_CHAT_TOPIC).get(0));

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
                "SELECT payload FROM outbox_message WHERE destination = ? ORDER BY id", String.class, destination);
    }
}
