package com.betterschool.domain.community.service.post;

import com.betterschool.domain.community.model.entity.PostEntity;
import com.betterschool.domain.community.model.valobj.FeedPageVO;
import com.betterschool.domain.community.model.valobj.PostReviewTimeoutVO;

import java.util.List;

/**
 * 社区帖子领域服务：发布/列表/详情/删除/点赞/审核状态流转
 */
public interface ICommunityPostService {

    /** 发布（先审后发：落库即 PENDING_REVIEW，同步初筛由 case 层前置，命中即报错不落库） */
    String publishPost(String userId, String title, String content, List<String> images, String cover);

    /** 保存草稿（私有停靠：字段可缺省，不做初筛/信用分门槛，由 case 层声明；轮次 0，提交进待审时才 +1） */
    String savePostDraft(String userId, String title, String content, List<String> images, String cover);

    /** 编辑草稿（仅 DRAFT 可编辑，全量覆盖；归属与状态守卫在本方法内） */
    void updatePostDraft(String userId, String postId, String title, String content, List<String> images,
                         String cover);

    /** 取本人草稿（归属 + DRAFT 状态守卫；提交前校验用） */
    PostEntity getOwnedPostDraft(String userId, String postId);

    /** 提交草稿进待审（完整性与同步初筛由 case 层前置；命中或校验失败停留草稿） */
    void submitPostDraft(String userId, String postId);

    /** 撤回待审回草稿箱：PENDING_REVIEW → DRAFT，撤回后可再编辑重提 */
    void withdrawPost(String userId, String postId);

    /** 被拒转草稿箱（自救路径）：REJECTED → DRAFT，修改后重提轮次 +1 */
    void moveRejectedPostToDraft(String userId, String postId);

    /**
     * 主页 feed（feed-index-uv 票 04）：post_feed_index 索引序 + 游标分页（cursor 非空时
     * 忽略 page），索引查询失败降级回 page/pageSize 直查。liked 集合、摘要截断等后处理由调用方完成
     */
    FeedPageVO listPosts(String sort, String cursor, int page, int pageSize);

    /** 我的帖子（作者视角）：NORMAL/PENDING_REVIEW/DRAFT/REJECTED，合并点赞+浏览数 */
    List<PostEntity> listMyPosts(String userId, int page, int pageSize);

    long countPosts();

    /** 我的帖子总数（作者视角） */
    long countMyPosts(String userId);

    /** 我点赞过的帖子（按点赞时间倒序；点赞后帖子被驳回/待审仍展示，作者视角的"已删"除外） */
    List<PostEntity> listLikedPosts(String userId, int page, int pageSize);

    long countLikedPosts(String userId);

    /** 我评论过的帖子（去重，按最近评论时间倒序） */
    List<PostEntity> listCommentedPosts(String userId, int page, int pageSize);

    long countCommentedPosts(String userId);

    /** 某用户的公开帖（作者主页「笔记」tab）：仅 NORMAL，本人看自己也走此口径 */
    List<PostEntity> listUserPosts(String userId, int page, int pageSize);

    long countUserPosts(String userId);

    /** 某用户赞过的帖（作者主页「赞过」tab）：源帖仅 NORMAL，按点赞时间倒序；隐私开关他视生效 */
    List<PostEntity> listUserLikedPosts(String userId, String viewerId, int page, int pageSize);

    long countUserLikedPosts(String userId, String viewerId);

    /** 详情：浏览计数 +1，登录时返回是否已赞；待审核/已驳回帖仅作者本人可见；浏览量仅作者本人合并返回 */
    PostEntity postDetail(String postId, String currentUserId);

    /** 读单条帖（无副作用不计数，任何状态可读；审核消费者/通知场景用） */
    PostEntity getPost(String postId);

    /** 删除（作者可删自己 NORMAL/PENDING_REVIEW/REJECTED 帖，待审帖可撤） */
    void deletePost(String postId, String currentUserId);

    /** 审核通过：PENDING_REVIEW → NORMAL（幂等：已通过静默成功，驳回/删除后不可通过） */
    void approvePost(String postId);

    /** 审核驳回：PENDING_REVIEW → REJECTED（终态，作者可重发新帖） */
    void rejectPost(String postId);

    /** 举报核实下架：无条件软删（无作者校验，幂等：已下架静默成功） */
    void forceDeletePost(String postId);

    /** 待审超 24h 帖子扫描（24h 提醒任务用，带审核轮次供 dedup_key 区分重提轮） */
    List<PostReviewTimeoutVO> listReviewTimeoutPosts();

    /** feed 索引全量重建（feed-index-uv 票 07，兜底恢复手段：索引行与窄表/明细表漂移时手动触发） */
    void rebuildFeedIndex();

    /** 我的浏览历史（最近浏览倒序；已删/被拒的他人帖过滤，本人浏览过的待审/驳回/草稿帖照常展示） */
    List<PostEntity> listViewHistory(String userId, int page, int pageSize);

    long countViewHistory(String userId);

    void likePost(String postId, String userId);

    void unlikePost(String postId, String userId);

    /** 当前用户已点赞的帖子 id 集合（列表 liked 状态批量回显；userId 为 null 返回空集） */
    List<String> listLikedPostIds(String userId, List<String> postIds);

    /** 当前用户浏览过的帖子 id 集合（feed 已读灰显批量回显；userId 为 null 返回空集） */
    List<String> listViewedPostIds(String userId, List<String> postIds);
}
