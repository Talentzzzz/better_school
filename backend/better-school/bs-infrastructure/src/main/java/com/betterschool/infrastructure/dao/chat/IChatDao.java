package com.betterschool.infrastructure.dao.chat;

import com.betterschool.infrastructure.dao.chat.po.UserPairRoomPO;
import com.betterschool.infrastructure.dao.chat.po.MessagePO;
import com.betterschool.infrastructure.dao.chat.po.RoomPO;
import com.betterschool.infrastructure.dao.chat.po.RoomListItemPO;
import com.betterschool.infrastructure.dao.chat.po.RoomMemberPO;
import com.betterschool.infrastructure.dao.chat.po.RoomPeerRowPO;
import com.betterschool.infrastructure.dao.chat.po.TimelineDeliveryRowPO;
import com.betterschool.infrastructure.dao.chat.po.TimelinePO;
import com.betterschool.infrastructure.dao.chat.po.UserRoomPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * chat Mapper：SQL 定义在 app 模块 resources/mybatis/mapper/ChatMapper.xml。
 * 双维度拆分后的查询面：user_room（用户维度，贴 user_id 片键）只做 user_id 前缀查询，
 * 「这个会话有谁」一律走 room_member 名册（会话维度，贴 room_id 片键）
 */
@Mapper
public interface IChatDao {

    // ===== room =====

    int insertRoom(RoomPO roomPO);

    RoomPO selectRoomById(@Param("roomId") String roomId);

    int increaseLastSeq(@Param("roomId") String roomId);

    Long selectLastSeq(@Param("roomId") String roomId);

    /**
     * 会话列表三段查之①（user_room.idx_user_msg 主路径）：本人用户行分页（排除隐藏，last_msg_time 倒序），
     * 只带列表三件套列；room 真相与对家 user_id 由仓储按 room_id 集回查拼装（SQL 层零 JOIN）
     */
    List<RoomListItemPO> selectUserRoomPage(@Param("userId") String userId,
                                            @Param("offset") int offset, @Param("limit") int limit);

    /** 会话列表三段查之②：按 room_id 集批量回查会话行（lastSeq 未读数真源 + source/initiator_id） */
    List<RoomPO> selectRoomsByIds(@Param("roomIds") List<String> roomIds);

    /** 会话列表三段查之③：本批会话的对家名册行（1v1 恒两行名册，每会话恰出一行对家） */
    List<RoomPeerRowPO> selectPeerMembers(@Param("roomIds") List<String> roomIds,
                                          @Param("userId") String userId);

    long countRoomsForUser(@Param("userId") String userId);

    // ===== user_pair_room（用户对 → 1v1 会话映射，建会话的幂等裁决者） =====

    /** 建会话事务的收尾闸门：撞 uk_pair 即 DuplicateKeyException，整个事务回滚 */
    int insertUserPairRoom(UserPairRoomPO pairPO);

    String selectUserPairRoomId(@Param("userMin") String userMin, @Param("userMax") String userMax);

    // ===== room_member（会话维度名册，「这个会话有谁」，每会话恒两行） =====

    int insertRoomMembers(@Param("members") List<RoomMemberPO> members);

    List<RoomMemberPO> listRoomMembers(@Param("roomId") String roomId);

    long countRoomMembers(@Param("roomId") String roomId);

    // ===== user_room（用户维度会话状态，每用户每会话一行） =====

    int insertUserRooms(@Param("userRooms") List<UserRoomPO> userRooms);

    UserRoomPO selectUserRoom(@Param("roomId") String roomId, @Param("userId") String userId);

    /** 成员页两段查之②：名册给定成员集的用户行批量回查（水位线拼装，SQL 层零 JOIN） */
    List<UserRoomPO> selectUserRooms(@Param("roomId") String roomId, @Param("userIds") List<String> userIds);

    int updateUserRoomReadSeq(@Param("roomId") String roomId, @Param("userId") String userId,
                              @Param("readSeq") long readSeq);

    int updateUserRoomHiddenAt(@Param("roomId") String roomId, @Param("userId") String userId,
                               @Param("hiddenAt") LocalDateTime hiddenAt);

    /** 新消息扇出时清空单个成员的隐藏位（删除的会话弹回；成员集由名册给定，逐用户执行） */
    int clearUserRoomHidden(@Param("roomId") String roomId, @Param("userId") String userId);

    /** 发送扇出时刷单个成员的列表副本（预览三件套；成员集由名册给定，逐用户执行） */
    int updateUserRoomPreview(@Param("roomId") String roomId, @Param("userId") String userId,
                              @Param("msgType") String msgType, @Param("preview") String preview,
                              @Param("lastMsgTime") LocalDateTime lastMsgTime);

    /** 撤回后替换单个成员的预览副本（room.last_seq 守卫；成员集由名册给定，逐用户执行） */
    int markUserRoomLastMsgRecalled(@Param("roomId") String roomId, @Param("userId") String userId,
                                    @Param("seq") Long seq, @Param("preview") String preview);

    // ===== message =====

    int insertMessage(MessagePO messagePO);

    MessagePO selectMessage(@Param("roomId") String roomId, @Param("seq") Long seq);

    MessagePO selectMessageByClientMsgId(@Param("roomId") String roomId, @Param("clientMsgId") String clientMsgId);

    int recallMessage(@Param("roomId") String roomId, @Param("seq") Long seq,
                      @Param("recalledAt") LocalDateTime recalledAt, @Param("recalledBy") String recalledBy);

    MessagePO selectMessageById(@Param("messageId") String messageId);

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
