package com.betterschool.infrastructure.dao.chat;

import com.betterschool.infrastructure.dao.chat.po.RoomListItem;
import com.betterschool.infrastructure.dao.chat.po.UserRoom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * user_room 表 DAO：SQL 定义在 app 模块 resources/mybatis/mapper/UserRoomMapper.xml。
 * 用户维度会话状态（贴 user_id 片键，uk 首列前置）：只做 user_id 前缀查询，
 * 「这个会话有谁」一律走 room_member 名册（会话维度，贴 room_id 片键）
 */
@Mapper
public interface IUserRoomDao {

    /**
     * 会话列表四段查之①（user_room.idx_user_msg 主路径）：本人用户行分页（排除隐藏，last_msg_time 倒序），
     * 只带排序键列；room 真相、对家 user_id 与预览真源由仓储按 room_id 集回查拼装（SQL 层零 JOIN）
     */
    List<RoomListItem> selectUserRoomPage(@Param("userId") String userId,
                                            @Param("offset") int offset, @Param("limit") int limit);

    int insertUserRooms(@Param("userRooms") List<UserRoom> userRoomList);

    UserRoom selectUserRoom(@Param("roomId") String roomId, @Param("userId") String userId);

    /** 成员页两段查之②：名册给定成员集的用户行批量回查（水位线拼装，SQL 层零 JOIN） */
    List<UserRoom> selectUserRooms(@Param("roomId") String roomId, @Param("userIds") List<String> userIdList);

    int updateUserRoomReadSeq(@Param("roomId") String roomId, @Param("userId") String userId,
                              @Param("readSeq") long readSeq);

    int updateUserRoomHiddenAt(@Param("roomId") String roomId, @Param("userId") String userId);

    /** 新消息扇出时清空单个成员的隐藏位（删除的会话弹回；成员集由名册给定，逐用户执行） */
    int clearUserRoomHidden(@Param("roomId") String roomId, @Param("userId") String userId);

    /** 发送扇出时刷单个成员的列表排序键（last_msg_time；预览读时现算不落副本；成员集由名册给定，逐用户执行） */
    int updateUserRoomLastMsgTime(@Param("roomId") String roomId, @Param("userId") String userId,
                                  @Param("lastMsgTime") LocalDateTime lastMsgTime);
}
