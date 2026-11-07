package com.betterschool.infrastructure.dao.chat;

import com.betterschool.infrastructure.dao.chat.po.UserPairRoomPO;
import com.betterschool.infrastructure.dao.chat.po.MessagePO;
import com.betterschool.infrastructure.dao.chat.po.RoomPO;
import com.betterschool.infrastructure.dao.chat.po.RoomListItemPO;
import com.betterschool.infrastructure.dao.chat.po.RoomMemberPO;
import com.betterschool.infrastructure.dao.chat.po.RoomPeerRowPO;
import com.betterschool.infrastructure.dao.chat.po.TimelineDeliveryRowPO;
import com.betterschool.infrastructure.dao.chat.po.TimelinePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * chat Mapper：SQL 定义在 app 模块 resources/mybatis/mapper/ChatMapper.xml
 */
@Mapper
public interface IChatDao {

    // ===== room =====

    int insertRoom(RoomPO roomPO);

    RoomPO selectRoomById(@Param("roomId") String roomId);

    int increaseLastSeq(@Param("roomId") String roomId);

    Long selectLastSeq(@Param("roomId") String roomId);

    int updateMemberPreview(@Param("roomId") String roomId, @Param("msgType") String msgType,
                            @Param("preview") String preview, @Param("lastMsgAt") LocalDateTime lastMsgAt);

    /**
     * 会话列表三段查之①（room_member 单表）：本人成员行分页（排除隐藏，last_msg_at 倒序），
     * 只带列表三件套列；room 真相与对家 user_id 由仓储按 room_id 集回查拼装（SQL 层零 JOIN）
     */
    List<RoomListItemPO> selectRoomMemberPage(@Param("userId") String userId,
                                              @Param("offset") int offset, @Param("limit") int limit);

    /** 会话列表三段查之②：按 room_id 集批量回查会话行（lastSeq 未读数真源 + source/initiator_id） */
    List<RoomPO> selectRoomsByIds(@Param("roomIds") List<String> roomIds);

    /** 会话列表三段查之③：本批会话里的对家成员行（1v1 恒两行成员，每会话恰出一行对家） */
    List<RoomPeerRowPO> selectPeerMembers(@Param("roomIds") List<String> roomIds,
                                          @Param("userId") String userId);

    long countRoomsForUser(@Param("userId") String userId);

    // ===== user_pair_room（用户对 → 1v1 会话映射，建会话的幂等裁决者） =====

    /** 建会话事务的收尾闸门：撞 uk_pair 即 DuplicateKeyException，整个事务回滚 */
    int insertUserPairRoom(UserPairRoomPO pairPO);

    String selectUserPairRoomId(@Param("userMin") String userMin, @Param("userMax") String userMax);

    // ===== room_member（成员状态，每会话恒两行） =====

    int insertRoomMembers(@Param("members") List<RoomMemberPO> members);

    RoomMemberPO selectRoomMember(@Param("roomId") String roomId, @Param("userId") String userId);

    List<RoomMemberPO> listRoomMembers(@Param("roomId") String roomId);

    long countRoomMembers(@Param("roomId") String roomId);

    int updateLastReadSeq(@Param("roomId") String roomId, @Param("userId") String userId,
                          @Param("lastReadSeq") long lastReadSeq);

    int clearHidden(@Param("roomId") String roomId);

    int updateHiddenAt(@Param("roomId") String roomId, @Param("userId") String userId,
                       @Param("hiddenAt") LocalDateTime hiddenAt);

    // ===== message =====

    int insertMessage(MessagePO messagePO);

    MessagePO selectMessage(@Param("roomId") String roomId, @Param("seq") Long seq);

    MessagePO selectMessageByClientMsgId(@Param("roomId") String roomId, @Param("clientMsgId") String clientMsgId);

    int recallMessage(@Param("roomId") String roomId, @Param("seq") Long seq,
                      @Param("recalledAt") LocalDateTime recalledAt, @Param("recalledBy") String recalledBy);

    MessagePO selectMessageById(@Param("messageId") String messageId);

    /** 撤回后替换预览，last_seq 守卫防覆盖并发新消息 */
    int markLastMsgRecalled(@Param("roomId") String roomId, @Param("seq") Long seq, @Param("preview") String preview);

    List<MessagePO> selectMessagesBefore(@Param("roomId") String roomId, @Param("beforeSeq") Long beforeSeq,
                                         @Param("limit") int limit);

    // ===== timeline =====

    int insertTimelines(@Param("timelines") List<TimelinePO> timelines);

    /** 增量补拉两段查之①（timeline 单表）：本人投递行（msg_id + delivery_seq 升序）；消息全量仓储回查拼装 */
    List<TimelineDeliveryRowPO> selectTimelineRows(@Param("userId") String userId,
                                                   @Param("afterDeliverySeq") long afterDeliverySeq,
                                                   @Param("limit") int limit);

    /** 增量补拉两段查之②：按 msg_id 集批量回查消息全量 */
    List<MessagePO> selectMessagesByIds(@Param("msgIds") List<String> msgIds);

    // ===== user_seq =====

    /** 计数器行不存在时按 seq=N 建行，存在则 +N */
    int upsertIncreaseUserSeq(@Param("userId") String userId, @Param("n") int n);

    Long selectUserSeq(@Param("userId") String userId);
}
