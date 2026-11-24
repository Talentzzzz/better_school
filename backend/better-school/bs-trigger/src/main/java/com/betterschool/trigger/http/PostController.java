package com.betterschool.trigger.http;

import com.betterschool.api.dto.CommentReplyResponseDTO;
import com.betterschool.api.dto.PostCommentRequestDTO;
import com.betterschool.api.dto.PostCommentResponseDTO;
import com.betterschool.api.dto.PostDraftSaveRequestDTO;
import com.betterschool.api.dto.PostPublishRequestDTO;
import com.betterschool.api.dto.PostResponseDTO;
import com.betterschool.api.dto.PostSummaryResponseDTO;
import com.betterschool.api.dto.UserCommentResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;
import com.betterschool.usecase.post.CommentLikeCase;
import com.betterschool.usecase.post.CommentPublishCase;
import com.betterschool.usecase.post.PostLikeCase;
import com.betterschool.usecase.post.PostPublishCase;
import com.betterschool.domain.post.model.entity.PostCommentEntity;
import com.betterschool.domain.post.service.post.IPostService;
import com.betterschool.domain.post.model.entity.PostEntity;
import com.betterschool.domain.post.model.valobj.FeedPageVO;
import com.betterschool.domain.post.model.valobj.PostStatusVO;
import com.betterschool.domain.post.service.post.IPostService;
import com.betterschool.trigger.http.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 帖子 HTTP 入口：浏览免登录，发布/评论/点赞需登录
 */
@RestController
@RequestMapping("/api/community")
@RequiredArgsConstructor
public class PostController {

    private static final int MAX_PAGE_SIZE = 50;
    /** feed 摘要截断长度（约前 50 字，文字小卡渲染用） */
    private static final int FEED_SUMMARY_MAX_LENGTH = 50;

    private final IPostService postService;
    private final PostPublishCase postPublishCase;
    private final CommentPublishCase commentPublishCase;
    private final PostLikeCase postLikeCase;
    private final CommentLikeCase commentLikeCase;

    @GetMapping("/posts")
    public Response<PageResult<PostSummaryResponseDTO>> listPosts(
            @RequestParam(defaultValue = "time") String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        // 索引 + 游标分页（票 04）：total 降口径 -1 不再精确 COUNT，续页靠 cursor；
        // 索引查询失败降级直查时 indexPath=false，total 仍精确
        FeedPageVO feedPage = postService.listPosts(sort, cursor, page, pageSize);
        List<PostEntity> postList = feedPage.getPosts();
        long total = feedPage.isIndexPath() ? -1 : postService.countPosts();
        String currentUserId = SecurityUtils.currentUserId();
        Set<String> likedIds = likedIdsOf(postList, currentUserId);
        List<PostSummaryResponseDTO> dtoList = postList.stream()
                .map(post -> toSummaryDTO(post, false, likedIds.contains(post.getPostId()), ContentMode.SUMMARY)).toList();
        // 已读灰显（feed-index-uv 票 05）：页内 post_id 批量回查浏览明细，登录用户带 viewed 标记（未登录 false）
        Set<String> viewedIds = viewedIdsOf(postList, currentUserId);
        dtoList.forEach(dto -> dto.setViewed(viewedIds.contains(dto.getPostId())));
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList, feedPage.getNextCursor()));
    }

    @GetMapping("/posts/mine")
    public Response<PageResult<PostSummaryResponseDTO>> listMyPosts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String currentUserId = SecurityUtils.currentUserId();
        long total = postService.countMyPosts(currentUserId);
        List<PostEntity> postList = postService.listMyPosts(currentUserId, page, pageSize);
        List<PostSummaryResponseDTO> dtoList = postList.stream()
                .map(post -> toSummaryDTO(post, true, false, ContentMode.FULL))
                .toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    @GetMapping("/posts/liked")
    public Response<PageResult<PostSummaryResponseDTO>> listLikedPosts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String currentUserId = SecurityUtils.currentUserId();
        long total = postService.countLikedPosts(currentUserId);
        List<PostEntity> postList = postService.listLikedPosts(currentUserId, page, pageSize);
        List<PostSummaryResponseDTO> dtoList = postList.stream()
                .map(post -> toSummaryDTO(post, false, true, ContentMode.FULL)).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    @GetMapping("/posts/commented")
    public Response<PageResult<PostSummaryResponseDTO>> listCommentedPosts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String currentUserId = SecurityUtils.currentUserId();
        long total = postService.countCommentedPosts(currentUserId);
        List<PostEntity> postList = postService.listCommentedPosts(currentUserId, page, pageSize);
        Set<String> likedIds = likedIdsOf(postList, currentUserId);
        List<PostSummaryResponseDTO> dtoList = postList.stream()
                .map(post -> toSummaryDTO(post, false, likedIds.contains(post.getPostId()), ContentMode.FULL)).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    @GetMapping("/posts/history")
    public Response<PageResult<PostSummaryResponseDTO>> listViewHistory(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        // GET /api/community/** 在 Security 白名单内（匿名可达）：浏览历史是个人数据，匿名一律空页
        String currentUserId = SecurityUtils.currentUserId();
        if (currentUserId == null) {
            return Response.ok(new PageResult<>(page, pageSize, 0, List.of()));
        }
        long total = postService.countViewHistory(currentUserId);
        List<PostEntity> postList = postService.listViewHistory(currentUserId, page, pageSize);
        Set<String> likedIds = likedIdsOf(postList, currentUserId);
        List<PostSummaryResponseDTO> dtoList = postList.stream()
                .map(post -> toSummaryDTO(post, false, likedIds.contains(post.getPostId()), ContentMode.SUMMARY)).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    @GetMapping("/users/{userId}/posts")
    public Response<PageResult<PostSummaryResponseDTO>> listUserPosts(
            @PathVariable String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long total = postService.countUserPosts(userId);
        List<PostEntity> postList = postService.listUserPosts(userId, page, pageSize);
        Set<String> likedIds = likedIdsOf(postList, SecurityUtils.currentUserId());
        List<PostSummaryResponseDTO> dtoList = postList.stream()
                .map(post -> toSummaryDTO(post, false, likedIds.contains(post.getPostId()), ContentMode.SUMMARY)).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    @GetMapping("/users/{userId}/liked")
    public Response<PageResult<PostSummaryResponseDTO>> listUserLikedPosts(
            @PathVariable String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String viewerId = SecurityUtils.currentUserId();
        long total = postService.countUserLikedPosts(userId, viewerId);
        List<PostEntity> postList = postService.listUserLikedPosts(userId, viewerId, page, pageSize);
        List<PostSummaryResponseDTO> dtoList = postList.stream()
                .map(post -> toSummaryDTO(post, false, true, ContentMode.SUMMARY)).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    @GetMapping("/users/{userId}/comments")
    public Response<PageResult<UserCommentResponseDTO>> listUserComments(
            @PathVariable String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String viewerId = SecurityUtils.currentUserId();
        long total = postService.countCommentsByUser(userId, viewerId);
        List<PostCommentEntity> commentList = postService.listCommentsByUser(userId, viewerId, page, pageSize);
        List<UserCommentResponseDTO> dtoList = commentList.stream().map(this::toUserCommentDTO).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    @PostMapping("/posts")
    public Response<String> publishPost(@Valid @RequestBody PostPublishRequestDTO request) {
        String postId = postPublishCase.publishPost(SecurityUtils.currentUserId(),
                request.getTitle(), request.getContent(), request.getImages(), request.getCover());
        return Response.ok(postId);
    }

    @PostMapping("/posts/draft")
    public Response<String> savePostDraft(@Valid @RequestBody PostDraftSaveRequestDTO request) {
        String postId = postPublishCase.savePostDraft(SecurityUtils.currentUserId(),
                request.getTitle(), request.getContent(), request.getImages(), request.getCover());
        return Response.ok(postId);
    }

    @PutMapping("/posts/{postId}/draft")
    public Response<Void> updatePostDraft(@PathVariable String postId,
                                          @Valid @RequestBody PostDraftSaveRequestDTO request) {
        postPublishCase.updatePostDraft(SecurityUtils.currentUserId(), postId,
                request.getTitle(), request.getContent(), request.getImages(), request.getCover());
        return Response.ok(null);
    }

    @PostMapping("/posts/{postId}/submit")
    public Response<Void> submitPostDraft(@PathVariable String postId) {
        postPublishCase.submitPostDraft(SecurityUtils.currentUserId(), postId);
        return Response.ok(null);
    }

    @GetMapping("/posts/{postId}")
    public Response<PostResponseDTO> postDetail(@PathVariable String postId) {
        String currentUserId = SecurityUtils.currentUserId();
        PostEntity postEntity = postService.postDetail(postId, currentUserId);
        return Response.ok(toPostResponseDTO(postEntity, currentUserId));
    }

    @DeleteMapping("/posts/{postId}")
    public Response<Void> deletePost(@PathVariable String postId) {
        postService.deletePost(postId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @PostMapping("/posts/{postId}/like")
    public Response<Void> likePost(@PathVariable String postId) {
        postLikeCase.like(postId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @DeleteMapping("/posts/{postId}/like")
    public Response<Void> unlikePost(@PathVariable String postId) {
        postService.unlikePost(postId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @PostMapping("/comments/{commentId}/like")
    public Response<Void> likeComment(@PathVariable String commentId) {
        commentLikeCase.like(commentId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @DeleteMapping("/comments/{commentId}/like")
    public Response<Void> unlikeComment(@PathVariable String commentId) {
        commentLikeCase.unlike(commentId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @GetMapping("/posts/{postId}/comments")
    public Response<PageResult<PostCommentResponseDTO>> listComments(
            @PathVariable String postId,
            @RequestParam(defaultValue = "hot") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        // total 为他人可见口径（不含待审）：sort=new 时作者行数可能多于 total（多出自己的审核中评论）；
        // sort=hot 只收已过审根评论、不自见，行数恒等于 total（票 01，默认档也是 hot）
        long total = postService.countVisibleRootComments(postId);
        List<PostCommentEntity> commentList =
                postService.listComments(postId, SecurityUtils.currentUserId(), sort, page, pageSize);
        Set<String> likedIds = likedCommentIdsOf(commentList, SecurityUtils.currentUserId());
        List<PostCommentResponseDTO> dtoList = commentList.stream()
                .map(comment -> toCommentResponseDTO(comment, likedIds.contains(comment.getCommentId()))).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    @PostMapping("/posts/{postId}/comments")
    public Response<PostCommentResponseDTO> addComment(@PathVariable String postId, @Valid @RequestBody PostCommentRequestDTO request) {
        PostCommentEntity commentEntity = commentPublishCase.addComment(postId, SecurityUtils.currentUserId(), request.getContent());
        return Response.ok(toCommentResponseDTO(commentEntity));
    }

    @GetMapping("/comments/{commentId}/replies")
    public Response<PageResult<CommentReplyResponseDTO>> listReplies(
            @PathVariable String commentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) Integer pageSize) {
        page = Math.max(page, 1);
        // 楼内分页约定：默认首页 3 条、之后每页 10 条；显式传 pageSize 时按常规等距分页
        int size = pageSize != null ? Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE) : (page == 1 ? 3 : 10);
        int offset = pageSize != null ? (page - 1) * size : (page == 1 ? 0 : 3 + (page - 2) * 10);
        String viewerId = SecurityUtils.currentUserId();
        long total = postService.countReplies(commentId, viewerId);
        List<PostCommentEntity> replyList = postService.listReplies(commentId, viewerId, offset, size);
        Set<String> likedIds = likedCommentIdsOf(replyList, SecurityUtils.currentUserId());
        List<CommentReplyResponseDTO> dtoList = replyList.stream()
                .map(reply -> toReplyResponseDTO(reply, likedIds.contains(reply.getCommentId()))).toList();
        return Response.ok(new PageResult<>(page, size, total, dtoList));
    }

    @PostMapping("/comments/{commentId}/replies")
    public Response<CommentReplyResponseDTO> addReply(@PathVariable String commentId, @Valid @RequestBody PostCommentRequestDTO request) {
        PostCommentEntity replyEntity = commentPublishCase.addReply(commentId, SecurityUtils.currentUserId(), request.getContent());
        return Response.ok(toReplyResponseDTO(replyEntity));
    }

    @DeleteMapping("/comments/{commentId}")
    public Response<Void> deleteComment(@PathVariable String commentId) {
        postService.deleteComment(commentId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    private PostResponseDTO toPostResponseDTO(PostEntity postEntity, String currentUserId) {
        PostResponseDTO dto = new PostResponseDTO();
        dto.setPostId(postEntity.getPostId());
        dto.setUserId(postEntity.getUserId());
        dto.setNickname(postEntity.getAuthorNickname());
        dto.setAvatar(postEntity.getAuthorAvatar());
        dto.setTitle(postEntity.getTitle());
        dto.setContent(postEntity.getContent());
        dto.setImages(postEntity.getImages());
        dto.setCover(postEntity.displayCover());
        // 原始独立封面（未回退相册）：草稿编辑回填用，避免展示口径被固化为独立封面
        dto.setIndependentCover(postEntity.getCover());
        dto.setStatus(postEntity.getStatus().name());
        dto.setLikeCount(postEntity.getLikeCount());
        dto.setCommentCount(postEntity.getCommentCount());
        // 浏览量仅作者本人可见
        dto.setViewCount(postEntity.isOwner(currentUserId) ? postEntity.getViewCount() : null);
        dto.setLiked(postEntity.isLiked());
        dto.setCreatedAt(postEntity.getCreatedAt());
        return dto;
    }

    /** 当前用户已点赞的帖子 id 集合（未登录为空集；批量查询避免 N+1） */
    private Set<String> likedIdsOf(List<PostEntity> postList, String currentUserId) {
        List<String> postIds = postList.stream().map(PostEntity::getPostId).toList();
        return new HashSet<>(postService.listLikedPostIds(currentUserId, postIds));
    }

    /** 当前用户浏览过的帖子 id 集合（未登录为空集；批量查询避免 N+1） */
    private Set<String> viewedIdsOf(List<PostEntity> postList, String currentUserId) {
        List<String> postIds = postList.stream().map(PostEntity::getPostId).toList();
        return new HashSet<>(postService.listViewedPostIds(currentUserId, postIds));
    }

    /** 当前用户已点赞的评论 id 集合（根评论与子回复通用；未登录为空集；批量查询避免 N+1） */
    private Set<String> likedCommentIdsOf(List<PostCommentEntity> commentList, String currentUserId) {
        List<String> commentIds = commentList.stream().map(PostCommentEntity::getCommentId).toList();
        return new HashSet<>(postService.listLikedCommentIds(currentUserId, commentIds));
    }

    /**
     * 列表摘要转换：self=true 作者视角（"我的"接口）——额外返回浏览量与审核状态；
     * liked：登录用户点赞状态（null 未登录）；contentMode 决定正文字段：主页 feed 截断摘要，
     * "我的"/点赞/评论过列表全量正文（客户端搜索过滤用），搜索列表不填
     */
    private PostSummaryResponseDTO toSummaryDTO(PostEntity postEntity, boolean self, boolean liked,
                                                ContentMode contentMode) {
        PostSummaryResponseDTO dto = new PostSummaryResponseDTO();
        dto.setPostId(postEntity.getPostId());
        dto.setTitle(postEntity.getTitle());
        dto.setCover(postEntity.displayCover());
        dto.setUserId(postEntity.getUserId());
        dto.setNickname(postEntity.getAuthorNickname());
        dto.setAvatar(postEntity.getAuthorAvatar());
        if (self) {
            dto.setViewCount(postEntity.getViewCount());
            dto.setStatus(postEntity.getStatus().name());
        }
        dto.setLikeCount(postEntity.getLikeCount());
        dto.setCreatedAt(postEntity.getCreatedAt());
        dto.setLiked(liked);
        switch (contentMode) {
            case FULL -> dto.setContent(postEntity.getContent());
            case SUMMARY -> dto.setContent(summarize(postEntity.getContent()));
        }
        return dto;
    }

    /** feed 摘要：正文截断至约前 50 字（null 安全） */
    private String summarize(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= FEED_SUMMARY_MAX_LENGTH
                ? content
                : content.substring(0, FEED_SUMMARY_MAX_LENGTH);
    }

    /** 列表正文字段口径 */
    private enum ContentMode {
        /** 全量正文（"我的"/点赞/评论过列表，客户端搜索过滤用） */
        FULL,
        /** 截断摘要（主页 feed，约前 50 字） */
        SUMMARY
    }

    private PostCommentResponseDTO toCommentResponseDTO(PostCommentEntity commentEntity, boolean liked) {
        PostCommentResponseDTO dto = new PostCommentResponseDTO();
        dto.setCommentId(commentEntity.getCommentId());
        dto.setPostId(commentEntity.getPostId());
        dto.setUserId(commentEntity.getUserId());
        dto.setNickname(commentEntity.getAuthorNickname());
        dto.setContent(commentEntity.getContent());
        dto.setLikeCount(commentEntity.getLikeCount());
        dto.setReplyCount(commentEntity.getReplyCount());
        dto.setLiked(liked);
        dto.setCreatedAt(commentEntity.getCreatedAt());
        return dto;
    }

    private PostCommentResponseDTO toCommentResponseDTO(PostCommentEntity commentEntity) {
        return toCommentResponseDTO(commentEntity, false);
    }

    private UserCommentResponseDTO toUserCommentDTO(PostCommentEntity commentEntity) {
        UserCommentResponseDTO dto = new UserCommentResponseDTO();
        dto.setCommentId(commentEntity.getCommentId());
        dto.setContent(commentEntity.getContent());
        dto.setLikeCount(commentEntity.getLikeCount());
        dto.setCreatedAt(commentEntity.getCreatedAt());
        dto.setPostId(commentEntity.getPostId());
        dto.setPostTitle(commentEntity.getPostTitle());
        dto.setPostCover(commentEntity.getPostCover());
        return dto;
    }

    private CommentReplyResponseDTO toReplyResponseDTO(PostCommentEntity replyEntity, boolean liked) {
        CommentReplyResponseDTO dto = new CommentReplyResponseDTO();
        dto.setCommentId(replyEntity.getCommentId());
        dto.setPostId(replyEntity.getPostId());
        dto.setParentId(replyEntity.getParentId());
        dto.setUserId(replyEntity.getUserId());
        dto.setNickname(replyEntity.getAuthorNickname());
        dto.setContent(replyEntity.getContent());
        dto.setLikeCount(replyEntity.getLikeCount());
        dto.setLiked(liked);
        dto.setCreatedAt(replyEntity.getCreatedAt());
        dto.setReplyToUserId(replyEntity.getReplyToUserId());
        dto.setReplyToNickname(replyEntity.getReplyToNickname());
        return dto;
    }

    private CommentReplyResponseDTO toReplyResponseDTO(PostCommentEntity replyEntity) {
        return toReplyResponseDTO(replyEntity, false);
    }
}
