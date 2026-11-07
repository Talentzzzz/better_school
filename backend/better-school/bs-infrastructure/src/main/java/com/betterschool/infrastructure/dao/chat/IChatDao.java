package com.betterschool.infrastructure.dao.chat;

import com.betterschool.infrastructure.dao.chat.po.UserPairRoom;
import com.betterschool.infrastructure.dao.chat.po.Message;
import com.betterschool.infrastructure.dao.chat.po.Room;
import com.betterschool.infrastructure.dao.chat.po.RoomListItem;
import com.betterschool.infrastructure.dao.chat.po.RoomMember;
import com.betterschool.infrastructure.dao.chat.po.RoomPeerRow;
import com.betterschool.infrastructure.dao.chat.po.TimelineDeliveryRow;
import com.betterschool.infrastructure.dao.chat.po.Timeline;
import com.betterschool.infrastructure.dao.chat.po.UserRoom;
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

    int insertRoom(Room room);

    Room selectRoomById(@Param("roomId") String roomId);

    int increaseLastSeq(@Param("roomId") String roomId);

    Long selectLastSeq(@Param("roomId") String roomId);

    /**
     * 会话列表四段查之①（user_room.idx_user_msg 主路径）：本人用户行分页（排除隐藏，last_msg_time 倒序），
     * 只带排序键列；room 真相、对家 user_id 与预览真源由仓储按 room_id 集回查拼装（SQL 层零 JOIN）
     */
    List<RoomListItem> selectUserRoomPage(@Param("userId") String userId,
                                            @Param("offset") int offset, @Param("limit") int limit);

    /** 会话列表三段查之②：按 room_id 集批量回查会话行（lastSeq 未读数真源 + source/creator_id） */
    List<Room> selectRoomsByIds(@Param("roomIds") List<String> roomIds);

    /** 会话列表三段查之③：本批会话的对家名册行（1v1 恒两行名册，每会话恰出一行对家） */
    List<RoomPeerRow> selectPeerMembers(@Param("roomIds") List<String> roomIds,
                                          @Param("userId") String userId);

    long countRoomsForUser(@Param("userId") String userId);

    // ===== user_pair_room（用户对 → 1v1 会话映射，建会话的幂等裁决者） =====

    /** 建会话事务的收尾闸门：撞 uk_pair 即 DuplicateKeyException，整个事务回滚 */
    int insertUserPairRoom(UserPairRoom pair);

    String selectUserPairRoomId(@Param("userMin") String userMin, @Param("userMax") String userMax);

    // ===== room_member（会话维度名册，「这个会话有谁」，每会话恒两行） =====

    int insertRoomMembers(@Param("members") List<RoomMember> members);

    List<RoomMember> listRoomMembers(@Param("roomId") String roomId);

    long countRoomMembers(@Param("roomId") String roomId);

    // ===== user_room（用户维度会话状态，每用户每会话一行） =====

    int insertUserRooms(@Param("userRooms") List<UserRoom> userRooms);

    UserRoom selectUserRoom(@Param("roomId") String roomId, @Param("userId") String userId);

    /** 成员页两段查之②：名册给定成员集的用户行批量回查（水位线拼装，SQL 层零 JOIN） */
    List<UserRoom> selectUserRooms(@Param("roomId") String roomId, @Param("userIds") List<String> userIds);

    int updateUserRoomReadSeq(@Param("roomId") String roomId, @Param("userId") String userId,
                              @Param("readSeq") long readSeq);

    int updateUserRoomHiddenAt(@Param("roomId") String roomId, @Param("userId") String userId);

    /** 新消息扇出时清空单个成员的隐藏位（删除的会话弹回；成员集由名册给定，逐用户执行） */
    int clearUserRoomHidden(@Param("roomId") String roomId, @Param("userId") String userId);

    /** 发送扇出时刷单个成员的列表排序键（last_msg_time；预览读时现算不落副本；成员集由名册给定，逐用户执行） */
    int updateUserRoomLastMsgTime(@Param("roomId") String roomId, @Param("userId") String userId,
                                  @Param("lastMsgTime") LocalDateTime lastMsgTime);

    // ===== message =====

    int insertMessage(Message message);

    Message selectMessage(@Param("roomId") String roomId, @Param("seq") Long seq);

    Message selectMessageByClientMsgId(@Param("roomId") String roomId, @Param("clientMsgId") String clientMsgId);

    int recallMessage(@Param("roomId") String roomId, @Param("seq") Long seq,
                      @Param("recalledBy") String recalledBy);

    Message selectMessageById(@Param("messageId") String messageId);

    List<Message> selectMessagesBefore(@Param("roomId") String roomId, @Param("beforeSeq") Long beforeSeq,
                                         @Param("limit") int limit);

    // ===== timeline =====

    int insertTimelines(@Param("timelines") List<Timeline> timelines);

    /** 增量补拉两段查之①（timeline 单表）：本人投递行（msg_id + delivery_seq 升序）；消息全量仓储回查拼装 */
    List<TimelineDeliveryRow> selectTimelineRows(@Param("userId") String userId,
                                                   @Param("afterDeliverySeq") long afterDeliverySeq,
                                                   @Param("limit") int limit);

    /** 增量补拉两段查之②：按 msg_id 集批量回查消息全量 */
    List<Message> selectMessagesByIds(@Param("msgIds") List<String> msgIds);

    // ===== user_seq =====

    /** 计数器行不存在时按 seq=N 建行，存在则 +N */
    int upsertIncreaseUserSeq(@Param("userId") String userId, @Param("n") int n);

    Long selectUserSeq(@Param("userId") String userId);
}
