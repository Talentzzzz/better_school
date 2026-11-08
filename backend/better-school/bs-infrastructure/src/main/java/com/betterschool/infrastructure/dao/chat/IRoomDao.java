package com.betterschool.infrastructure.dao.chat;

import com.betterschool.infrastructure.dao.chat.po.Room;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * room 表 DAO：SQL 定义在 app 模块 resources/mybatis/mapper/RoomMapper.xml。
 * room 是会话真相表（source/creator_id/last_seq——未读数真源）
 */
@Mapper
public interface IRoomDao {

    int insertRoom(Room room);

    Room selectRoomById(@Param("roomId") String roomId);

    int increaseLastSeq(@Param("roomId") String roomId);

    Long selectLastSeq(@Param("roomId") String roomId);

    /** 会话列表三段查之②：按 room_id 集批量回查会话行（lastSeq 未读数真源 + source/creator_id） */
    List<Room> selectRoomsByIds(@Param("roomIds") List<String> roomIdList);
}
