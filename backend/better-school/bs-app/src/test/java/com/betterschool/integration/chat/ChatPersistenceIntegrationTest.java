package com.betterschool.integration.chat;

import com.betterschool.domain.chat.adapter.repository.IChatRepository;
import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.entity.RoomEntity;
import com.betterschool.domain.chat.model.entity.RoomMemberEntity;
import com.betterschool.domain.chat.model.entity.TimelineEntity;
import com.betterschool.domain.chat.model.entity.UserRoomEntity;
import com.betterschool.domain.chat.model.valobj.MsgTypeVO;
import com.betterschool.domain.chat.model.valobj.RoomListItemVO;
import com.betterschool.integration.support.CommunityApiTestBase;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * chat 持久化层造数集成测试（落地票 01）：建 room/扇出/取号六表状态（room + user_room 用户维度 +
 * room_member 名册 + message + timeline + user_seq）+ user_pair_room 配对闸门语义。
 * 双维度拆分（chat-member-table-refactor 票 01）：user_room 只装 per-user 状态，名册装「这个会话有谁」。
 * 发送链路的域服务归票 02，本测试直接在事务内驱动仓储原语；预览去副本（票 02）：user_room 只落
 * last_msg_time 排序键，预览读时按 message 真相现算
 */
public class ChatPersistenceIntegrationTest extends CommunityApiTestBase {

    private static final String USER_A = "a101a101-a101-a101-a101-a101a101a101";
    private static final String USER_B = "b102b102-b102-b102-b102-b102b102b102";

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private IChatRepository chatRepository;

    @Test
    void userPairRoomUnique_duplicateCreationRejectedByPairGate() {
        RoomEntity roomEntity = new RoomEntity();
        roomEntity.setCreatorId(USER_A);
        String roomId = chatRepository.createDirectRoom(roomEntity, USER_B).getRoomId();
        assertUuidId(roomId); // uuid-biz-id 票 04：roomId 必须是可解析的 UUID 字符串

        RoomEntity foundEntity = chatRepository.findDirectRoom(USER_A, USER_B);
        assertThat(foundEntity.getRoomId()).as("1v1 查重应命中同人唯一会话").isEqualTo(roomId);
        assertThat(queryLong("SELECT COUNT(1) FROM user_pair_room WHERE user_min = '" + USER_A + "'"
                + " AND user_max = '" + USER_B + "' AND room_id = '" + roomId + "'"))
                .as("建会话应同事务落一行配对（规范序在前）").isEqualTo(1);

        // pair 表收尾闸门：同一对人再建撞 uk_pair，事务内已写的 room/名册/user_room 行全部回滚
        RoomEntity againEntity = new RoomEntity();
        againEntity.setCreatorId(USER_B);
        assertThatThrownBy(() -> chatRepository.createDirectRoom(againEntity, USER_A))
                .as("user_pair_room.uk_pair 应拦截同一对人重复建会话")
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(queryLong("SELECT COUNT(1) FROM user_pair_room")).isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(1) FROM room")).isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(1) FROM user_room WHERE room_id = '" + roomId + "'")).isEqualTo(2);
        assertThat(queryLong("SELECT COUNT(1) FROM room_member WHERE room_id = '" + roomId + "'")).isEqualTo(2);
        assertThat(queryLong("SELECT MIN(join_seq) FROM room_member WHERE room_id = '" + roomId + "'"))
                .as("1v1 建会话名册 join_seq 入群水位恒 0").isEqualTo(0);
    }

    @Test
    void sendMessagePersistsAllSixTables() {
        String roomId = transactionTemplate.execute(status -> {
            String newRoomId = createDirectRoom(USER_A, USER_B);
            insertMembersAndUserRooms(newRoomId, List.of(USER_A, USER_B));

            long seq = chatRepository.increaseLastSeq(newRoomId);
            assertThat(seq).as("首条消息序号应从 1 起").isEqualTo(1);

            MessageEntity messageEntity = textMessage(newRoomId, USER_A, seq, "同学你好，帖子还在吗？");
            chatRepository.insertMessage(messageEntity);
            assertUuidId(messageEntity.getMessageId()); // uuid-biz-id 票 04：insertMessage 应回填 UUID 字符串业务键

            // 发送幂等回查：同 (roomId, clientMsgId) 收敛同一条
            assertThat(chatRepository.findMessageByClientMsgId(newRoomId, messageEntity.getClientMsgId()).getMessageId())
                    .as("uk_client_msg 回查应取回原消息")
                    .isEqualTo(messageEntity.getMessageId());

            // 扇出：双方各取一个 delivery_seq 后插时间线
            long deliveryA = chatRepository.increaseUserSeq(USER_A, 1);
            long deliveryB = chatRepository.increaseUserSeq(USER_B, 1);
            chatRepository.insertTimelines(List.of(
                    timeline(USER_A, newRoomId, seq, messageEntity.getMessageId(), deliveryA),
                    timeline(USER_B, newRoomId, seq, messageEntity.getMessageId(), deliveryB)));

            chatRepository.updateUserRoomLastMsgTime(newRoomId, List.of(USER_A, USER_B),
                    messageEntity.getCreatedAt());
            return newRoomId;
        });

        // room：纯取号器
        assertThat(queryLong("SELECT last_seq FROM room WHERE room_id = '" + roomId + "'")).isEqualTo(1);

        // room_member 名册：会话维度「这个会话有谁」，双方各一行，join_seq 入群水位 0
        assertThat(queryLong("SELECT COUNT(1) FROM room_member WHERE room_id = '" + roomId + "'")).isEqualTo(2);
        assertThat(queryLong("SELECT MIN(join_seq) FROM room_member WHERE room_id = '" + roomId + "'")).isEqualTo(0);

        // user_room：双方用户行，列表排序键（last_msg_time）落用户维度、水位线 0；预览不落副本
        //（票 02：读时按 message 真相现算），未读数不落库，由 room.last_seq 现算
        assertThat(queryLong("SELECT COUNT(1) FROM user_room WHERE room_id = '" + roomId + "'")).isEqualTo(2);
        assertThat(queryLong("SELECT MIN(read_seq) FROM user_room WHERE room_id = '" + roomId + "'")).isEqualTo(0);
        assertThat(queryLong("SELECT COUNT(1) FROM user_room WHERE room_id = '" + roomId + "'"
                + " AND last_msg_time IS NOT NULL")).isEqualTo(2);

        // 列表读路径（四段查④）：预览按 message 真相现算，无副本列也出全文
        List<RoomListItemVO> items = chatRepository.listRoomItems(USER_A, 0, 10);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getRoomId()).isEqualTo(roomId);
        assertThat(items.get(0).getLastMsgType()).as("lastMsgType 读时按 message 真相现算").isEqualTo(MsgTypeVO.TEXT);
        assertThat(items.get(0).getLastMsgPreview()).as("预览读时现算 = message 原文").isEqualTo("同学你好，帖子还在吗？");

        // message：内容中心一行，NORMAL + client_msg_id
        assertThat(queryLong("SELECT seq FROM message WHERE room_id = '" + roomId + "'")).isEqualTo(1);
        assertThat(queryString("SELECT state FROM message WHERE room_id = '" + roomId + "'")).isEqualTo("NORMAL");
        assertThat(queryString("SELECT client_msg_id FROM message WHERE room_id = '" + roomId + "'")).isEqualTo("client-msg-1");
        assertThat(queryString("SELECT content FROM message WHERE room_id = '" + roomId + "'")).isEqualTo("同学你好，帖子还在吗？");

        // timeline：N 个成员 N 行，per-user delivery_seq 从 1 起
        assertThat(queryLong("SELECT COUNT(1) FROM timeline WHERE room_id = '" + roomId + "'")).isEqualTo(2);
        assertThat(queryLong("SELECT delivery_seq FROM timeline WHERE room_id = '" + roomId + "' AND user_id = '" + USER_A + "'")).isEqualTo(1);
        assertThat(queryLong("SELECT delivery_seq FROM timeline WHERE room_id = '" + roomId + "' AND user_id = '" + USER_B + "'")).isEqualTo(1);

        // user_seq：每用户一行计数器
        assertThat(queryLong("SELECT seq FROM user_seq WHERE user_id = '" + USER_A + "'")).isEqualTo(1);
        assertThat(queryLong("SELECT seq FROM user_seq WHERE user_id = '" + USER_B + "'")).isEqualTo(1);
    }

    @Test
    void userSeqCounterAccumulatesAcrossMessages() {
        transactionTemplate.executeWithoutResult(status -> {
            String newRoomId = createDirectRoom(USER_A, USER_B);
            insertMembersAndUserRooms(newRoomId, List.of(USER_A, USER_B));

            // 第一条：双方各 +1
            long firstDeliverySeqA = chatRepository.increaseUserSeq(USER_A, 1);
            assertThat(firstDeliverySeqA).isEqualTo(1);

            // 第二条：计数器累加，行不存在时按 N 建行
            assertThat(chatRepository.increaseUserSeq(USER_A, 1)).isEqualTo(2);
            assertThat(chatRepository.increaseUserSeq(USER_B, 1)).isEqualTo(1);

            // 批量取号：新用户一次 +3，分配区间 [1,3]
            assertThat(chatRepository.increaseUserSeq("u999-standalone", 3)).isEqualTo(3);
        });
        assertThat(queryLong("SELECT seq FROM user_seq WHERE user_id = 'u999-standalone'")).isEqualTo(3);
    }

    @Test
    void memberStateUpdatesFollowSpecSemantics() {
        transactionTemplate.executeWithoutResult(status -> {
            String newRoomId = createDirectRoom(USER_A, USER_B);
            insertMembersAndUserRooms(newRoomId, List.of(USER_A, USER_B));

            // 已读水位线只前进不回退
            chatRepository.updateUserReadSeq(newRoomId, USER_B, 5);
            chatRepository.updateUserReadSeq(newRoomId, USER_B, 3);
            assertThat(chatRepository.findUserRoom(newRoomId, USER_B).getReadSeq()).isEqualTo(5);

            // 隐藏位清空（新消息弹回）只清该会话
            jdbcTemplate.update("UPDATE user_room SET hidden_at = NOW() WHERE room_id = '" + newRoomId + "'");
            chatRepository.clearHidden(newRoomId, List.of(USER_A, USER_B));
            assertThat(chatRepository.findUserRoom(newRoomId, USER_A).getHiddenAt()).isNull();
        });
    }

    // ---------- 造数 helpers ----------

    private String createDirectRoom(String userA, String userB) {
        RoomEntity roomEntity = new RoomEntity();
        roomEntity.setCreatorId(userA);
        chatRepository.insertRoom(roomEntity);
        return roomEntity.getRoomId();
    }

    /** 名册与用户状态成对造数（与建会话事务同构：1v1 恒两行名册 + 两行 user_room） */
    private void insertMembersAndUserRooms(String roomId, List<String> userIds) {
        chatRepository.insertRoomMembers(userIds.stream().map(userId -> {
            RoomMemberEntity memberEntity = new RoomMemberEntity();
            memberEntity.setRoomId(roomId);
            memberEntity.setUserId(userId);
            return memberEntity;
        }).toList());
        chatRepository.insertUserRooms(userIds.stream().map(userId -> {
            UserRoomEntity userRoomEntity = new UserRoomEntity();
            userRoomEntity.setRoomId(roomId);
            userRoomEntity.setUserId(userId);
            return userRoomEntity;
        }).toList());
    }

    private MessageEntity textMessage(String roomId, String fromUserId, long seq, String content) {
        MessageEntity messageEntity = new MessageEntity();
        messageEntity.setRoomId(roomId);
        messageEntity.setSeq(seq);
        messageEntity.setFromUserId(fromUserId);
        messageEntity.setMsgType(MsgTypeVO.TEXT);
        messageEntity.setContent(content);
        messageEntity.setClientMsgId("client-msg-" + seq);
        return messageEntity;
    }

    private TimelineEntity timeline(String userId, String roomId, long seq, String msgId, long deliverySeq) {
        TimelineEntity timelineEntity = new TimelineEntity();
        timelineEntity.setUserId(userId);
        timelineEntity.setRoomId(roomId);
        timelineEntity.setSeq(seq);
        timelineEntity.setMsgId(msgId);
        timelineEntity.setDeliverySeq(deliverySeq);
        return timelineEntity;
    }

    private Object query(String sql) {
        return jdbcTemplate.queryForObject(sql, Object.class);
    }

    private long queryLong(String sql) {
        return ((Number) jdbcTemplate.queryForObject(sql, Object.class)).longValue();
    }

    private String queryString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }
}
