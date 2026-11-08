package com.betterschool.integration.chat;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话来源集成测试（营销票 03）：市集「私信联系」两入口带 source 建会话、首页入口不带；
 * 来源只在首次建会话写入（首来源钉死）；会话列表按来源出对家角色（同一会话两侧各自看到自己的对家）。
 *
 * <p>seam = REST 入口 + 外部可观察状态（HTTP 响应 + room 表列）。
 */
public class ChatRoomSourceIntegrationTest extends CommunityApiTestBase {

    @Test
    @DisplayName("市集入口建会话：来源与创建人落库，列表出对家角色；对方视角角色相反")
    void marketEntryWritesSourceAndServesPeerRolePerViewer() {
        Session seller = registerAndLogin("13800007001", "来源卖家");
        Session buyer = registerAndLogin("13800007002", "来源买家");

        String roomId = createRoom(buyer, seller.userId(), "PRODUCT");
        sendText(roomId, buyer, "m1", "东西还在吗");

        // 创建人（商品详情「联系卖家」= 买家）：对家是卖家
        JsonNode forBuyer = onlyRoom(buyer);
        assertThat(forBuyer.path("roomId").asText()).isEqualTo(roomId);
        assertThat(forBuyer.path("source").asText()).isEqualTo("PRODUCT");
        assertThat(forBuyer.path("peerRole").asText()).as("买家视角对家=卖家").isEqualTo("SELLER");
        assertThat(forBuyer.path("peerNickname").asText()).isEqualTo("来源卖家");

        // 另一方（卖家）看同一条会话：对家是买家 —— 同一会话两侧各自看到自己的对家
        JsonNode forSeller = onlyRoom(seller);
        assertThat(forSeller.path("roomId").asText()).isEqualTo(roomId);
        assertThat(forSeller.path("source").asText()).isEqualTo("PRODUCT");
        assertThat(forSeller.path("peerRole").asText()).as("卖家视角对家=买家").isEqualTo("BUYER");
        assertThat(forSeller.path("peerNickname").asText()).isEqualTo("来源买家");

        assertThat(jdbcTemplate.queryForObject("SELECT source FROM room WHERE room_id = ?", String.class, roomId))
                .isEqualTo("PRODUCT");
        assertThat(jdbcTemplate.queryForObject("SELECT creator_id FROM room WHERE room_id = ?", String.class, roomId))
                .isEqualTo(buyer.userId());
    }

    @Test
    @DisplayName("跑腿入口：两侧同角色标签（跑手）")
    void errandEntryServesFixedPeerRoleOnBothSides() {
        Session userA = registerAndLogin("13800007011", "来源跑腿A");
        Session userB = registerAndLogin("13800007012", "来源跑腿B");

        String errandRoom = createRoom(userA, userB.userId(), "ERRAND");
        sendText(errandRoom, userA, "e1", "到楼下了");

        assertThat(peerRoleInRoom(userA, errandRoom)).isEqualTo("TAKER");
        assertThat(peerRoleInRoom(userB, errandRoom)).as("跑腿来源列定不了方向，对家恒为跑手").isEqualTo("TAKER");
    }

    @Test
    @DisplayName("首来源钉死：先建闲置会话，再从跑腿入口进同一对人——同一条会话、来源不变")
    void firstSourceIsPinned() {
        Session userA = registerAndLogin("13800007021", "钉死A");
        Session userB = registerAndLogin("13800007022", "钉死B");

        String roomId = createRoom(userA, userB.userId(), "PRODUCT");
        String again = createRoom(userB, userA.userId(), "ERRAND");

        assertThat(again).as("user_pair_room.uk_pair 只认人对，来源不拆会话").isEqualTo(roomId);
        assertThat(jdbcTemplate.queryForObject("SELECT source FROM room WHERE room_id = ?", String.class, roomId))
                .as("二次进入不覆盖首来源").isEqualTo("PRODUCT");
        assertThat(jdbcTemplate.queryForObject("SELECT creator_id FROM room WHERE room_id = ?", String.class, roomId))
                .as("创建人也不被覆盖").isEqualTo(userA.userId());
    }

    @Test
    @DisplayName("首页入口不带来源：来源与角色为空（这类会话不进市集消息页）")
    void homeEntryLeavesSourceNull() {
        Session userA = registerAndLogin("13800007031", "首页A");
        Session userB = registerAndLogin("13800007032", "首页B");

        String roomId = createRoom(userA, userB.userId(), null);
        sendText(roomId, userA, "h1", "你好");

        JsonNode room = onlyRoom(userA);
        assertThat(room.path("source").isNull()).as("首页私信入口不写来源").isTrue();
        assertThat(room.path("peerRole").isNull()).as("无来源即无角色标签").isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT source FROM room WHERE room_id = ?", String.class, roomId)).isNull();

        // 之后即使从市集入口再进，也不补写（来源只在首次建会话时落）
        createRoom(userA, userB.userId(), "ERRAND");
        assertThat(jdbcTemplate.queryForObject("SELECT source FROM room WHERE room_id = ?", String.class, roomId)).isNull();
    }

    @Test
    @DisplayName("来源取值非法：400 参数错误，不建会话")
    void illegalSourceRejected() {
        Session userA = registerAndLogin("13800007041", "非法A");
        Session userB = registerAndLogin("13800007042", "非法B");

        Map<String, Object> body = new HashMap<>();
        body.put("targetUserId", userB.userId());
        body.put("source", "TAOBAO");
        JsonNode response = postJson("/api/chat/rooms/direct", body, userA);

        assertThat(response.path("code").asText()).isEqualTo("400");
        assertThat(response.path("info").asText()).contains("会话来源");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM room", Integer.class)).isZero();
    }

    // ---------- helpers ----------

    private String createRoom(Session from, String targetUserId, String source) {
        Map<String, Object> body = new HashMap<>();
        body.put("targetUserId", targetUserId);
        if (source != null) {
            body.put("source", source);
        }
        JsonNode response = postJson("/api/chat/rooms/direct", body, from);
        assertSuccess(response, "建 1v1 会话");
        return response.path("data").path("roomId").asText();
    }

    private void sendText(String roomId, Session from, String clientMsgId, String content) {
        JsonNode response = postJson("/api/chat/rooms/messages",
                Map.of("roomId", roomId, "clientMsgId", clientMsgId, "msgType", "TEXT", "content", content), from);
        assertSuccess(response, "发消息");
    }

    private JsonNode onlyRoom(Session session) {
        JsonNode data = getJson("/api/chat/rooms?page=1&pageSize=50", session).path("data");
        assertThat(data.path("list").size()).as("本用例只有一个会话").isEqualTo(1);
        return data.path("list").get(0);
    }

    private String peerRoleInRoom(Session session, String roomId) {
        JsonNode data = getJson("/api/chat/rooms?page=1&pageSize=50", session).path("data");
        for (JsonNode room : data.path("list")) {
            if (room.path("roomId").asText().equals(roomId)) {
                return room.path("peerRole").asText();
            }
        }
        throw new AssertionError("会话不在列表中：" + roomId);
    }
}
