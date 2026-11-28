package com.betterschool.infrastructure.dao.chat;

import com.betterschool.infrastructure.dao.chat.po.UserPairRoom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * user_pair_room 表 DAO：SQL 定义在 app 模块 resources/mybatis/mapper/UserPairRoomMapper.xml。
 * 用户对 → 1v1 会话映射，建会话的幂等裁决者
 */
@Mapper
public interface IUserPairRoomDao {

    /** 建会话事务的收尾闸门：撞 uk_pair 即 DuplicateKeyException，整个事务回滚 */
    int insertUserPairRoom(UserPairRoom userPairRoom);

    String selectUserPairRoomId(@Param("userMin") String userMin, @Param("userMax") String userMax);
}
