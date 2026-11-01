package com.betterschool.api;

import com.betterschool.api.dto.CommentReplyResponseDTO;
import com.betterschool.api.dto.PostCommentRequestDTO;
import com.betterschool.api.dto.PostDraftSaveRequestDTO;
import com.betterschool.api.dto.PostCommentResponseDTO;
import com.betterschool.api.dto.PostPublishRequestDTO;
import com.betterschool.api.dto.PostResponseDTO;
import com.betterschool.api.dto.PostSummaryResponseDTO;
import com.betterschool.api.dto.UserCommentResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;

/**
 * 社区服务契约：HTTP 层由 Controller 实现，后续微服务化时由 Dubbo Provider 实现
 */
public interface ICommunityService {

    /**
     * 访客列表：只返回摘要字段，不暴露浏览量。走 feed 索引 + 游标分页：cursor 参数非空
     * 则按 keyset 续页（page 被忽略），响应 data.cursor 非 null = 下一页必须带它（total 此时降口径为 -1，
     * 不再精确 COUNT）；cursor 为 null 的响应按 page/pageSize 兼容续页
     */
    Response<PageResult<PostSummaryResponseDTO>> listPosts(String sort, String cursor, int page, int pageSize);

    /** 我的帖子（作者视角）：含待审/被驳回，返回浏览量 */
    Response<PageResult<PostSummaryResponseDTO>> listMyPosts(int page, int pageSize);

    /** 我点赞过的帖子（按点赞时间倒序） */
    Response<PageResult<PostSummaryResponseDTO>> listLikedPosts(int page, int pageSize);

    /** 我评论过的帖子（去重，按最近评论时间倒序） */
    Response<PageResult<PostSummaryResponseDTO>> listCommentedPosts(int page, int pageSize);

    /** 我的浏览历史（最近浏览倒序；已删/被拒的他人帖过滤。GET 白名单内匿名可达，匿名一律空页） */
    Response<PageResult<PostSummaryResponseDTO>> listViewHistory(int page, int pageSize);

    /** 某用户的公开帖（作者主页「笔记」tab，免登录）：仅 NORMAL */
    Response<PageResult<PostSummaryResponseDTO>> listUserPosts(String userId, int page, int pageSize);

    /** 某用户赞过的帖（作者主页「赞过」tab，免登录）：源帖仅 NORMAL，按点赞时间倒序 */
    Response<PageResult<PostSummaryResponseDTO>> listUserLikedPosts(String userId, int page, int pageSize);

    /** 某用户的评论（作者主页「评论」tab，免登录）：评论与源帖均 NORMAL，按评论时间倒序 */
    Response<PageResult<UserCommentResponseDTO>> listUserComments(String userId, int page, int pageSize);

    Response<String> publishPost(PostPublishRequestDTO request);

    /** 保存帖子草稿（字段可缺省，不做初筛/信用分门槛），返回 postId */
    Response<String> savePostDraft(PostDraftSaveRequestDTO request);

    /** 编辑帖子草稿（仅 DRAFT 可编辑，全量覆盖；待审中需先撤回） */
    Response<Void> updatePostDraft(String postId, PostDraftSaveRequestDTO request);

    /** 提交草稿进待审：正文必填校验 + 同步初筛，命中/缺项停留草稿 */
    Response<Void> submitPostDraft(String postId);

    /** 撤回待审回草稿箱（仅作者、仅 PENDING_REVIEW） */
    Response<Void> withdrawPost(String postId);

    /** 被拒帖转草稿箱（仅作者、仅 REJECTED） */
    Response<Void> moveRejectedPostToDraft(String postId);

    Response<PostResponseDTO> postDetail(String postId);

    Response<Void> deletePost(String postId);

    Response<Void> likePost(String postId);

    Response<Void> unlikePost(String postId);

    /** 点赞评论（根评论与子回复通用）：仅 NORMAL 可见评论可赞；重复赞报业务错误 */
    Response<Void> likeComment(String commentId);

    /** 取消评论点赞：未赞报业务错误；不撤回已发的被赞通知 */
    Response<Void> unlikeComment(String commentId);

    /** 根评论列表：sort=hot|new（默认 hot，最热按 like_count 降序）；作者附带可见自己审核中的评论 */
    Response<PageResult<PostCommentResponseDTO>> listComments(String postId, String sort, int page, int pageSize);

    /** 发布根评论（零同步审核：落库即待审，异步审核通过后对他人可见）；返回完整新评论 DTO（乐观插顶用） */
    Response<PostCommentResponseDTO> addComment(String postId, PostCommentRequestDTO request);

    /** 楼内子回复平铺分页；pageSize 缺省时首页 3 条、之后每页 10 条 */
    Response<PageResult<CommentReplyResponseDTO>> listReplies(String commentId, int page, Integer pageSize);

    /** 回复评论（根评论或楼内子回复）；root/parent 由服务端按 ADR-0001 规则计算；返回完整新回复 DTO */
    Response<CommentReplyResponseDTO> addReply(String commentId, PostCommentRequestDTO request);

    Response<Void> deleteComment(String commentId);
}
