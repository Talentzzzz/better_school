package com.betterschool.infrastructure.dao.chat;

import com.betterschool.infrastructure.dao.chat.po.RoomMember;
import com.betterschool.infrastructure.dao.chat.po.RoomPeerRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * room_member 表 DAO：SQL 定义在 app 模块 resources/mybatis/mapper/RoomMemberMapper.xml。
 * 会话维度名册「这个会话有谁」，每会话恒两行（1v1）
 */
@Mapper
public interface IRoomMemberDao {

    int insertRoomMembers(@Param("members") List<RoomMember> roomMemberList);

    List<RoomMember> listRoomMembers(@Param("roomId") String roomId);

    long countRoomMembers(@Param("roomId") String roomId);

    /** 会话列表三段查之③：本批会话的对家名册行（1v1 恒两行名册，每会话恰出一行对家） */
    List<RoomPeerRow> selectPeerMembers(@Param("roomIds") List<String> roomIdList,
                                          @Param("userId") String userId);
}
