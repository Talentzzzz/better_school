package com.betterschool.infrastructure.dao.chat;

import com.betterschool.infrastructure.dao.chat.po.DirectRoomPairPO;
import com.betterschool.infrastructure.dao.chat.po.MessagePO;
import com.betterschool.infrastructure.dao.chat.po.RoomPO;
import com.betterschool.infrastructure.dao.chat.po.RoomListItemPO;
import com.betterschool.infrastructure.dao.chat.po.RoomMemberPO;
import com.betterschool.infrastructure.dao.chat.po.TimelineMessagePO;
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

    int updateMemberCopies(@Param("roomId") String roomId, @Param("seq") long seq, @Param("msgType") String msgType,
                           @Param("preview") String preview, @Param("lastMsgAt") LocalDateTime lastMsgAt);

    List<RoomListItemPO> selectRoomItems(@Param("userId") String userId,
                                            @Param("offset") int offset, @Param("limit") int limit);

    long countRoomsForUser(@Param("userId") String userId);

    // ===== direct_room_pair（1v1 配对映射，建会话的幂等裁决者） =====

    /** 建会话事务的收尾闸门：撞 uk_pair 即 DuplicateKeyException，整个事务回滚 */
    int insertDirectRoomPair(DirectRoomPairPO pairPO);

    String selectDirectRoomId(@Param("userMin") String userMin, @Param("userMax") String userMax);

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

    List<TimelineMessagePO> selectTimelineAfter(@Param("userId") String userId,
                                                @Param("afterDeliverySeq") long afterDeliverySeq,
                                                @Param("limit") int limit);

    // ===== user_seq =====

    /** 计数器行不存在时按 seq=N 建行，存在则 +N */
    int upsertIncreaseUserSeq(@Param("userId") String userId, @Param("n") int n);

    Long selectUserSeq(@Param("userId") String userId);
}
