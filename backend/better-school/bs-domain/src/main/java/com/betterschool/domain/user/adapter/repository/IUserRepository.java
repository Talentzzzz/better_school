package com.betterschool.domain.user.adapter.repository;

import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.domain.user.model.valobj.UserBriefVO;
import com.betterschool.domain.user.model.valobj.UserRoleEnum;
import com.betterschool.types.common.SearchPageVO;

import java.util.List;

/**
 * 用户仓储：覆盖 user 领域服务所需全部持久化能力（含验证码存取），由 infrastructure 层实现
 */
public interface IUserRepository {

    UserEntity findByPhone(String phone);

    UserEntity findById(String userId);

    /** 注册落库 + 注册成功消息 + 搜索同步消息：一个仓储原子用例（同事务写 outbox），返回回填业务 id 的实体 */
    UserEntity saveRegistered(UserEntity userEntity);

    void update(UserEntity userEntity);

    /** 编辑资料：仅写 signature/gender（昵称/头像/角色/状态/信用分不归本动作） */
    void updateProfile(String userId, String signature, String gender);

    /** 隐私开关：仅写 show_liked/show_comments */
    void updatePrivacy(String userId, boolean showLiked, boolean showComments);

    /** 获赞：该用户 NORMAL 帖 like_count 之和（不含评论获赞，不含待审/驳回/草稿帖） */
    long sumNormalPostLikeCount(String userId);

    /**
     * 隐私开关「公开我的赞过」对他视是否可见：开关关闭且 viewer ≠ owner 时为 false；
     * viewer = owner（本人看自己）恒 true。开关判据在 user 域，社区域按此谓词过滤
     */
    boolean isLikedVisibleTo(String ownerId, String viewerId);

    /** 同上，「公开我的评论」 */
    boolean isCommentsVisibleTo(String ownerId, String viewerId);

    void storeVerifyCode(String phone, String code, long ttlSeconds);

    boolean verifyVerifyCode(String phone, String code);

    /** 签发登录令牌（登录/注册成功后调用，实现层委托 JwtService） */
    String generateToken(String userId, String role);

    /** 全部用户 ID（站内公告广播落库用；一次性取全量，不是循环） */
    List<String> listAllUserIds();

    /** 搜索索引全量重建的游标页：id > lastUserId 按 id 升序取 limit 条（无界结果集一律游标化） */
    List<String> listUserIdsAfter(String lastUserId, int limit);

    /** 指定角色全部用户 ID（审核超时提醒收件人等） */
    List<String> listUserIdsByRole(UserRoleEnum userRoleVO);

    /** 按 id 列表查询（搜索同步/搜索命中反查用；返回顺序与入参无关，调用方按 id 重排） */
    List<UserEntity> listByIds(List<String> userIdList);

    /**
     * 按 id 列表查展示信息（昵称/头像，窄投影）：会话列表对家、消息发送者等纯展示场景用。
     * 与 listByIds 分开是刻意的——展示路径不该把 phone 等敏感列读进内存
     */
    List<UserBriefVO> listBriefByIds(List<String> userIdList);

    // ---------- 搜索索引（技术能力，落本域仓储：backend/CLAUDE.md §1） ----------

    /** 用户检索：ES 命中只回 id（相关度倒序）与近似总数，展示字段由领域服务回库组装 */
    SearchPageVO<String> searchUserIds(String keyword, int page, int pageSize);

    /** 按 id 反查用户刷新搜索文档：仍 NORMAL 则写入、否则删除（幂等自愈，重复消息无副作用） */
    void syncUserSearchDoc(String userId);

    /** 用户索引全量重建（清空 + 游标分批回填），返回回填条数 */
    int rebuildUserSearchIndex();
}
