package com.betterschool.infrastructure.dao.user;

import com.betterschool.infrastructure.dao.user.po.UserPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户 Mapper：SQL 定义在 app 模块 resources/mybatis/mapper/UserMapper.xml
 */
@Mapper
public interface IUserDao {

    UserPO selectByPhone(@Param("phone") String phone);

    UserPO selectById(@Param("userId") String userId);

    int insert(UserPO userPO);

    int updateById(UserPO userPO);

    /** 编辑资料：仅写 signature/gender 两列 */
    int updateProfile(@Param("userId") String userId, @Param("signature") String signature,
                      @Param("gender") String gender);

    /** 隐私开关：仅写 show_liked/show_comments 两列 */
    int updatePrivacy(@Param("userId") String userId, @Param("showLiked") boolean showLiked,
                      @Param("showComments") boolean showComments);

    /** 全量用户 id（站内公告广播落库用；一次性取全量，不是循环） */
    List<String> selectAllIds();

    /** 搜索索引全量重建的游标页：id > lastId 按 id 升序取 limit 条 */
    List<String> selectIdsAfter(@Param("lastUserId") String lastUserId, @Param("limit") int limit);

    /** 指定角色全部用户 ID（审核超时提醒收件人等） */
    List<String> selectIdsByRole(@Param("role") String role);

    /** 按 id 列表查询（搜索同步反查/搜索结果组装用） */
    List<UserPO> selectByIds(@Param("ids") List<String> userIds);
}
