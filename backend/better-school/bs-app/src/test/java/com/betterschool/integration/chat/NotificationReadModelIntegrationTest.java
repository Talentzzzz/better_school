package com.betterschool.integration.chat;

import com.betterschool.domain.notification.model.entity.NotificationEntity;
import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;
import com.betterschool.domain.notification.service.INotificationService;
import com.betterschool.integration.support.PostApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知读模型（营销票 02）集成测试：「scope + 水位线」照抄 chat 的序号模型——
 * 写入事务内 notification_seq 取号（seq 递增且 dedup 幂等不耗号）、未读数 = 计数器 − 水位（O(1)）、
 * 标已读 = 推水位且各 scope 互不误清、响应 read 字段由 seq ≤ 水位算出。
 * 写路径走事件驱动 seam：publish（业务事务内写 outbox）→ 消费方落库（{@link #deliver} helper），
 * HTTP 端点另测 scope 过滤与推水位。
 */
class NotificationReadModelIntegrationTest extends PostApiTestBase {

    @Autowired
    private INotificationService notificationService;

    @Test
    void 写入取号按scope独立递增未读数为计数器减水位() {
        var user = registerAndLogin("13900001001", "收件人");

        deliver(event(user.userId(), NotificationScopeVO.MARKET_PRODUCT,
                NotificationTypeVO.CREDIT_CHANGE, "订单动态", "您的商品订单已支付成功", "PRODUCT", "1", "D1"));
        deliver(event(user.userId(), NotificationScopeVO.MARKET_PRODUCT,
                NotificationTypeVO.CREDIT_CHANGE, "订单已取消", "超时未接单已取消", "PRODUCT", "1", "D2"));
        deliver(event(user.userId(), NotificationScopeVO.BOTTOM_SYSTEM,
                NotificationTypeVO.ANNOUNCEMENT, "平台公告", "系统维护通知", "BROADCAST", null, null));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT seq FROM notification WHERE dedup_key = 'D1'", Long.class))
                .as("同 scope 内 seq 从 1 递增").isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT seq FROM notification WHERE dedup_key = 'D2'", Long.class)).isEqualTo(2L);

        assertThat(notificationService.unreadCount(user.userId(), NotificationScopeVO.MARKET_PRODUCT))
                .as("未读数 = 计数器(2) − 水位(0)").isEqualTo(2L);
        assertThat(notificationService.unreadCount(user.userId(), NotificationScopeVO.BOTTOM_SYSTEM)).isEqualTo(1L);
        assertThat(notificationService.unreadCount(user.userId(), NotificationScopeVO.INTERACTION_LIKE))
                .as("从无通知的范围未读为 0").isZero();

        List<NotificationEntity> marketList = notificationService.listNotifications(
                user.userId(), NotificationScopeVO.MARKET_PRODUCT, 1, 10);
        assertThat(marketList).hasSize(2)
                .as("scope 过滤：系统范围通知不串进市集列表")
                .allSatisfy(n -> {
                    assertThat(n.getScope()).isEqualTo(NotificationScopeVO.MARKET_PRODUCT);
                    assertThat(n.isRead()).as("水位 0 ⇒ 全部未读").isFalse();
                });
        assertThat(marketList).as("seq 降序：最新在前")
                .extracting(NotificationEntity::getSeq).containsExactly(2L, 1L);
    }

    @Test
    void dedup幂等命中不耗号未读数不虚增() {
        var user = registerAndLogin("13900001002", "收件人");
        String dedupKey = "CREDIT_CHANGE:ADMIN:1:" + user.userId();

        deliver(event(user.userId(), NotificationScopeVO.BOTTOM_SYSTEM,
                NotificationTypeVO.CREDIT_CHANGE, "订单动态", "订单支付成功", "PRODUCT", "1", dedupKey));
        deliver(event(user.userId(), NotificationScopeVO.BOTTOM_SYSTEM,
                NotificationTypeVO.CREDIT_CHANGE, "订单动态", "订单支付成功", "PRODUCT", "1", dedupKey));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ?", Long.class, user.userId()))
                .as("重复只落一条").isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT seq FROM notification_seq WHERE user_id = ? AND scope = 'BOTTOM_SYSTEM'",
                Long.class, user.userId()))
                .as("幂等命中不耗 seq 号段").isEqualTo(1L);
        assertThat(notificationService.unreadCount(user.userId(), NotificationScopeVO.BOTTOM_SYSTEM)).isEqualTo(1L);
    }

    @Test
    void 推水位按scope独立新通知仍算未读() {
        var user = registerAndLogin("13900001003", "收件人");

        deliver(event(user.userId(), NotificationScopeVO.MARKET_PRODUCT,
                NotificationTypeVO.CREDIT_CHANGE, "订单动态", "第一批", "PRODUCT", "1", "E1"));
        deliver(event(user.userId(), NotificationScopeVO.BOTTOM_SYSTEM,
                NotificationTypeVO.CREDIT_CHANGE, "信用分变动", "+2", null, null, "E2"));

        notificationService.markRead(user.userId(), NotificationScopeVO.MARKET_PRODUCT);

        assertThat(notificationService.unreadCount(user.userId(), NotificationScopeVO.MARKET_PRODUCT))
                .as("市集范围已清").isZero();
        assertThat(notificationService.unreadCount(user.userId(), NotificationScopeVO.BOTTOM_SYSTEM))
                .as("系统范围不受误清").isEqualTo(1L);

        // 已读后再来新通知：旧行 read=true、新行 read=false，未读数回到 1
        deliver(event(user.userId(), NotificationScopeVO.MARKET_PRODUCT,
                NotificationTypeVO.CREDIT_CHANGE, "订单已取消", "第二批", "PRODUCT", "1", "E3"));
        List<NotificationEntity> marketList = notificationService.listNotifications(
                user.userId(), NotificationScopeVO.MARKET_PRODUCT, 1, 10);
        assertThat(marketList).hasSize(2);
        assertThat(marketList.get(0)).as("新通知（seq 2）仍未读")
                .matches(n -> n.getContent().equals("第二批") && !n.isRead());
        assertThat(marketList.get(1)).as("旧通知（seq 1）在水位下已读")
                .matches(n -> n.getContent().equals("第一批") && n.isRead());
        assertThat(notificationService.unreadCount(user.userId(), NotificationScopeVO.MARKET_PRODUCT)).isEqualTo(1L);

        // 水位只前进：重复 markRead 不回退，再次已读清零
        notificationService.markRead(user.userId(), NotificationScopeVO.MARKET_PRODUCT);
        assertThat(notificationService.unreadCount(user.userId(), NotificationScopeVO.MARKET_PRODUCT)).isZero();
    }

    @Test
    void http端点按scope取数推水位与未读数() {
        var user = registerAndLogin("13900001004", "收件人");

        deliver(event(user.userId(), NotificationScopeVO.BOTTOM_SYSTEM,
                NotificationTypeVO.ANNOUNCEMENT, "公告", "平台维护", "BROADCAST", null, null));
        deliver(event(user.userId(), NotificationScopeVO.INTERACTION_LIKE,
                NotificationTypeVO.POST_LIKED, "互动通知", "有人赞了你的帖子", "POST", "3", null));

        // unread-count 端点
        JsonNode unread = getJson("/api/chat/notifications/unread-count?scope=BOTTOM_SYSTEM", user);
        assertSuccess(unread, "未读数");
        assertThat(unread.path("data").path("count").asLong()).isEqualTo(1L);

        // scope 过滤列表：只回 BOTTOM_SYSTEM
        JsonNode list = getJson("/api/chat/notifications?scope=BOTTOM_SYSTEM&page=1&pageSize=10", user);
        assertSuccess(list, "范围列表");
        assertThat(list.path("data").path("total").asLong()).isEqualTo(1L);
        assertThat(list.path("data").path("list").get(0).path("type").asText()).isEqualTo("ANNOUNCEMENT");
        assertThat(list.path("data").path("list").get(0).path("read").asBoolean()).isFalse();

        // POST read 推水位：unread 归零、列表 read 置位
        assertSuccess(postJson("/api/chat/notifications/read", Map.of("scope", "BOTTOM_SYSTEM"), user), "推水位");
        assertThat(getJson("/api/chat/notifications/unread-count?scope=BOTTOM_SYSTEM", user)
                .path("data").path("count").asLong()).isZero();
        JsonNode listAfter = getJson("/api/chat/notifications?scope=BOTTOM_SYSTEM", user);
        assertThat(listAfter.path("data").path("list").get(0).path("read").asBoolean()).isTrue();
        // 其他范围水位不受影响
        assertThat(getJson("/api/chat/notifications/unread-count?scope=INTERACTION_LIKE", user)
                .path("data").path("count").asLong()).isEqualTo(1L);

        // 非法/空白 scope → 参数错误业务码
        JsonNode badScope = getJson("/api/chat/notifications/unread-count?scope=NO_SUCH_SCOPE", user);
        assertThat(badScope.path("code").asText()).isEqualTo("400");
        JsonNode blankScope = getJson("/api/chat/notifications/unread-count?scope=", user);
        assertThat(blankScope.path("code").asText()).as("unread-count 的 scope 必填，空白拒绝").isEqualTo("400");
    }

    // ---------- 事件驱动写路径 helper ----------

    private NotificationEventMessage event(String userId, NotificationScopeVO scope, NotificationTypeVO type,
                                           String title, String content, String refType, String refId,
                                           String dedupKey) {
        return NotificationEventMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .userId(userId)
                .scope(scope.name())
                .type(type.name())
                .title(title)
                .content(content)
                .refType(refType)
                .refId(refId)
                .dedupKey(dedupKey)
                .build();
    }

    /** publish（业务事务内写 outbox）→ 手动喂消费方落库（生产环境由 MQ 驱动同一入口） */
    private void deliver(NotificationEventMessage event) {
        notificationService.publish(event);
        consumeNotifyEvents();
    }
}
