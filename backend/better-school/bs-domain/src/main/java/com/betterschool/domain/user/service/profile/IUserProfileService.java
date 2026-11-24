package com.betterschool.domain.user.service.profile;

import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.types.common.SearchPageVO;

import java.util.Collection;
import java.util.List;

/**
 * 用户公开资料领域服务：作者主页与「我的」页资料区共用（本人看自己传自己的 userId）。
 * 只读接口免登录；编辑资料/隐私设置需登录（由触发层白名单区分）
 */
public interface IUserProfileService {

    /** 公开资料（用户不存在报 404）；不含手机号，调用方自行裁剪 */
    UserEntity getProfile(String userId);

    /**
     * 基本信息批量（跨服务同步边 / Dubbo 用）：营销三服务取昵称、头像、注册时间。
     * 与 getProfile 的区别是宽松语义——不存在的 id 直接跳过、不抛 404，调用方自行按 id 索引
     */
    List<UserEntity> listBasic(Collection<String> userIds);

    /** 获赞：该用户 NORMAL 帖 like_count 之和（不含评论获赞，不含待审/驳回/草稿帖） */
    long countReceivedLikes(String userId);

    /**
     * 编辑资料（整体覆盖可编辑的两字段）：signature 空串/null＝清空，≤40 字；gender null/空串＝不展示。
     * 昵称/头像不在此动作内（牵动全站展示口径，另立 effort）
     */
    UserEntity updateProfile(String userId, String signature, String gender);

    /** 隐私开关（部分更新：null＝该项不改）；返回更新后的用户 */
    UserEntity updatePrivacy(String userId, Boolean showLiked, Boolean showComments);

    /**
     * 站内搜索：昵称命中本域仓储的倒排索引后回库组装成实体（相关度顺序保持），
     * 只返回 NORMAL 用户——索引侧状态过滤是冗余兜底，这里才是最终判据
     */
    SearchPageVO<UserEntity> searchUsers(String keyword, int page, int pageSize);

    /** 搜索文档同步：按 id 反查，仍 NORMAL 则写入索引、否则删除（幂等自愈，重复消息无副作用） */
    void syncSearchDoc(String userId);

    /** 用户索引全量重建（清空 + 游标分批回填），返回回填条数 */
    int rebuildSearchIndex();
}
