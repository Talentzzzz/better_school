package com.betterschool.domain.post.service.post;

import com.betterschool.domain.post.adapter.port.IUserQueryPort;
import com.betterschool.domain.post.adapter.repository.IPostRepository;
import com.betterschool.domain.post.model.entity.PostCommentEntity;
import com.betterschool.domain.post.model.entity.PostEntity;
import com.betterschool.domain.post.model.valobj.PostUserVO;
import com.betterschool.domain.post.model.valobj.FeedCursorVO;
import com.betterschool.domain.post.model.valobj.FeedIndexItemVO;
import com.betterschool.domain.post.model.valobj.FeedPageVO;
import com.betterschool.domain.post.model.valobj.LikeStateVO;
import com.betterschool.domain.post.model.valobj.PostStatusVO;
import com.betterschool.types.common.SearchPageVO;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements IPostService {

    private static final int MAX_PAGE_SIZE = 50;

    private final IPostRepository postRepository;
    private final IUserQueryPort userQueryPort;

    @Override
    public String publishPost(String userId, String title, String content, List<String> images,
                              String cover) {
        // 信用分门槛由 case 层前置校验；落库即可见，搜索同步消息同事务落 outbox
        PostEntity postEntity = PostEntity.create(userId, title, content, images, cover);
        postRepository.save(postEntity);
        return postEntity.getPostId();
    }

    @Override
    public String savePostDraft(String userId, String title, String content, List<String> images,
                                String cover) {
        // 草稿为私有停靠内容：不做信用分门槛（提交时才校验），字段可缺省
        PostEntity postEntity = PostEntity.createDraft(userId, title, content, images, cover);
        postRepository.save(postEntity);
        return postEntity.getPostId();
    }

    @Override
    public void updatePostDraft(String userId, String postId, String title, String content,
                                List<String> images, String cover) {
        PostEntity postEntity = getOwnedPostDraft(userId, postId);
        postEntity.applyDraftContent(title, content, images, cover);
        postRepository.updateDraft(postEntity);
    }

    @Override
    public PostEntity getOwnedPostDraft(String userId, String postId) {
        PostEntity postEntity = postRepository.findPostById(postId);
        if (postEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "草稿不存在");
        }
        if (!postEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能操作自己发布的帖子");
        }
        if (postEntity.getStatus() != PostStatusVO.DRAFT) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "仅草稿可编辑");
        }
        return postEntity;
    }

    @Override
    public void submitPostDraft(String userId, String postId) {
        // 完整性校验由 case 层前置
        getOwnedPostDraft(userId, postId);
        if (!postRepository.submitDraftWithSearchSync(postId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "草稿状态已变化，请刷新后重试");
        }
    }

    @Override
    public FeedPageVO listPosts(String sort, String cursor, int page, int pageSize) {
        String sortType = "hot".equals(sort) ? "hot" : "time";
        FeedCursorVO cursorVO = cursor == null
                ? FeedCursorVO.firstPage(sortType)
                : FeedCursorVO.decode(cursor, sortType);
        try {
            return pageByFeedIndex(sortType, cursorVO, page, pageSize);
        } catch (BusinessException e) {
            throw e; // 游标非法是调用方错误，不是降级信号
        } catch (Exception e) {
            log.warn("feed 索引查询失败，降级 DB 直查：sort={}", sortType, e);
        }
        return legacyPage(sortType, page, pageSize);
    }

    /**
     * 索引路径：post_feed_index 按 (排序键, post_id) 索引序扫描出 id（多探一行探下页），
     * 再 {@code listByIds} 批量回查组装、按扫描序重排。下一页游标 = 本页最后一行的排序键锚点，
     * 只在确认有下一页（探到多一行）时发放；扫描为空 = 游标已越过末尾，返回空页（不发游标）
     */
    private FeedPageVO pageByFeedIndex(String sortType, FeedCursorVO cursorVO, int page, int pageSize) {
        List<FeedIndexItemVO> rows = postRepository.scanFeedIndex(sortType, cursorVO, pageSize + 1);
        if (rows.isEmpty()) {
            return new FeedPageVO(List.of(), null, true);
        }
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        List<String> postIds = rows.stream().map(FeedIndexItemVO::getPostId).toList();
        Map<String, PostEntity> byId = postRepository.listByIds(postIds).stream()
                .collect(Collectors.toMap(PostEntity::getPostId, Function.identity(), (a, b) -> a, HashMap::new));
        // 索引行缺 post 行（存量异常，重建 job 修）只跳过不补位：游标锚在扫描序上，补位会引入重复
        List<PostEntity> posts = postIds.stream()
                .filter(byId::containsKey)
                .map(byId::get)
                .toList();
        String nextCursor = hasMore
                ? FeedCursorVO.anchorOf(sortType, rows.get(rows.size() - 1)).encode()
                : null;
        return new FeedPageVO(posts, nextCursor, true);
    }

    /** 旧直查路径（降级）：offset 分页，精确 COUNT 由调用方按需另取 */
    private FeedPageVO legacyPage(String sortType, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return new FeedPageVO(postRepository.pageList(sortType, offset, pageSize), null, false);
    }

    @Override
    public List<PostEntity> listMyPosts(String userId, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return postRepository.pagePostsByUser(userId, offset, pageSize);
    }

    @Override
    public long countPosts() {
        return postRepository.count();
    }

    @Override
    public long countMyPosts(String userId) {
        return postRepository.countPostsByUser(userId);
    }

    @Override
    public List<PostEntity> listLikedPosts(String userId, int page, int pageSize) {
        return postRepository.pageLikedByUser(userId, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countLikedPosts(String userId) {
        return postRepository.countLikedByUser(userId);
    }

    @Override
    public List<PostEntity> listCommentedPosts(String userId, int page, int pageSize) {
        return postRepository.pageCommentedByUser(userId, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countCommentedPosts(String userId) {
        return postRepository.countCommentedByUser(userId);
    }

    @Override
    public List<PostEntity> listUserPosts(String userId, int page, int pageSize) {
        return postRepository.pageNormalByUser(userId, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countUserPosts(String userId) {
        return postRepository.countNormalByUser(userId);
    }

    @Override
    public List<PostEntity> listUserLikedPosts(String userId, String viewerId, int page, int pageSize) {
        if (!isLikedVisibleTo(userId, viewerId)) {
            return List.of();
        }
        return postRepository.pagePublicLikedByUser(userId, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countUserLikedPosts(String userId, String viewerId) {
        return isLikedVisibleTo(userId, viewerId) ? postRepository.countPublicLikedByUser(userId) : 0;
    }

    @Override
    public PostEntity postDetail(String postId, String currentUserId) {
        PostEntity postEntity = getVisiblePost(postId, currentUserId);
        // 浏览计数（UV 口径）：fire-and-forget，入队即返回（窗口过后一组一个事务落明细表 + 加速列）。
        // 允许丢、不影响本次请求结果，详情页读到的仍是库里的当前值；
        // 匿名访问（未登录）落不了明细行，按 UV 口径不计入
        postRepository.recordPostView(postId, currentUserId);
        if (currentUserId != null) {
            postEntity.setLiked(postRepository.isPostLiked(postId, currentUserId));
        }
        return postEntity;
    }

    @Override
    public PostEntity getPost(String postId) {
        return postRepository.findPostById(postId);
    }

    @Override
    public void deletePost(String postId, String currentUserId) {
        PostEntity postEntity = getVisiblePost(postId, currentUserId);
        if (!postEntity.isOwner(currentUserId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能删除自己发布的帖子");
        }
        postRepository.deleteWithSearchSync(postId);
    }

    @Override
    public void rebuildFeedIndex() {
        postRepository.rebuildFeedIndex();
    }

    @Override
    public List<PostEntity> listViewHistory(String userId, int page, int pageSize) {
        return postRepository.pageViewHistoryByUser(userId, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countViewHistory(String userId) {
        return postRepository.countViewHistoryByUser(userId);
    }

    @Override
    public void likePost(String postId, String userId) {
        getNormalPost(postId);
        // 合并写：窗口合并成一组一事务（唯一键裁决重复 + 计数同事务推进），返回时计数已精确
        LikeStateVO state = postRepository.likePostWithMergedCount(postId, userId);
        if (state == LikeStateVO.DUPLICATE) {
            // 重复赞是业务结果不是异常：唯一键裁决出来的「已经赞过」，对外语义与改造前一致
            throw new BusinessException(ResponseCode.PARAM_ERROR, "请勿重复点赞");
        }
    }

    @Override
    public void unlikePost(String postId, String userId) {
        getNormalPost(postId);
        if (postRepository.unlikePostWithMergedCount(postId, userId) == LikeStateVO.NOT_FOUND) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "尚未点赞");
        }
    }

    @Override
    public List<String> listLikedPostIds(String userId, List<String> postIds) {
        return postRepository.selectLikedPostIds(userId, postIds);
    }

    @Override
    public List<String> listViewedPostIds(String userId, List<String> postIds) {
        return postRepository.selectViewedPostIds(userId, postIds);
    }

    @Override
    public SearchPageVO<PostEntity> searchPosts(String keyword, int page, int pageSize) {
        // 索引只负责命中与排序：命中 id 回库批量反查，按 id 顺序重排（IN 查询不保序），
        // 命中后状态已变化的实体在这里被过滤掉——本域才是「公开」的最终判据
        SearchPageVO<String> idPage = postRepository.searchPostIds(keyword, page, pageSize);
        if (idPage.getItems().isEmpty()) {
            return new SearchPageVO<>(idPage.getTotal(), List.of());
        }
        Map<String, PostEntity> byId = postRepository.listByIds(idPage.getItems()).stream()
                .filter(post -> post.getStatus() == PostStatusVO.NORMAL)
                .collect(Collectors.toMap(PostEntity::getPostId, Function.identity()));
        return new SearchPageVO<>(idPage.getTotal(), idPage.getItems().stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList());
    }

    @Override
    public void syncSearchDoc(String postId) {
        postRepository.syncPostSearchDoc(postId);
    }

    @Override
    public int rebuildSearchIndex() {
        return postRepository.rebuildPostSearchIndex();
    }

    private PostEntity getNormalPost(String postId) {
        PostEntity postEntity = postRepository.findPostById(postId);
        if (postEntity == null || postEntity.getStatus() != PostStatusVO.NORMAL) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "帖子不存在");
        }
        return postEntity;
    }

    /** 可见性：NORMAL 全员可见；草稿帖仅作者本人可见（不公开） */
    private PostEntity getVisiblePost(String postId, String currentUserId) {
        PostEntity postEntity = postRepository.findPostById(postId);
        if (postEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "帖子不存在");
        }
        if (postEntity.getStatus() == PostStatusVO.NORMAL || postEntity.isOwner(currentUserId)) {
            return postEntity;
        }
        throw new BusinessException(ResponseCode.NOT_FOUND, "帖子不存在");
    }

    /**
     * 隐私开关「公开我的赞过」对他视是否可见：开关关闭且 viewer ≠ owner 时为 false；
     * viewer = owner（本人看自己）恒 true；profile 读不到（用户不存在）按可见处理（列表本就为空）。
     * 开关原始值由 user 只读 port 取回，判据留在本域
     */
    private boolean isLikedVisibleTo(String ownerId, String viewerId) {
        if (viewerId != null && viewerId.equals(ownerId)) {
            return true;
        }
        PostUserVO owner = userQueryPort.findById(ownerId);
        return owner == null || !Boolean.FALSE.equals(owner.getShowLiked());
    }
    @Override
    public List<PostCommentEntity> listComments(String postId, String viewerId, String sort, int page, int pageSize) {
        String sortType = "new".equals(sort) ? "new" : "hot";
        return postRepository.pageRootByPostId(postId, viewerId, sortType, (page - 1) * pageSize, pageSize);
    }
    @Override
    public long countVisibleRootComments(String postId) {
        return postRepository.countVisibleRootsByPostId(postId);
    }
    @Override
    public List<PostCommentEntity> listCommentsByUser(String userId, String viewerId, int page, int pageSize) {
        if (!isCommentsVisibleTo(userId, viewerId)) {
            return List.of();
        }
        return postRepository.pageCommentsByUser(userId, (page - 1) * pageSize, pageSize);
    }
    @Override
    public long countCommentsByUser(String userId, String viewerId) {
        return isCommentsVisibleTo(userId, viewerId) ? postRepository.countCommentsByUser(userId) : 0;
    }
    @Override
    public PostCommentEntity addComment(String postId, String userId, String content) {
        getNormalPost(postId);
        PostCommentEntity commentEntity = PostCommentEntity.create(postId, userId, content, PostStatusVO.NORMAL);
        publishVisible(commentEntity);
        commentEntity.setAuthorNickname(nicknameOf(userId));
        commentEntity.setReplyCount(0L);
        return commentEntity;
    }
    @Override
    public PostCommentEntity addReply(String targetCommentId, String userId, String content) {
        PostCommentEntity targetEntity = postRepository.findCommentById(targetCommentId);
        // 下架评论对所有人不可见，视同不存在
        if (targetEntity == null || targetEntity.getStatus() == PostStatusVO.DELETED) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "评论不存在");
        }
        // 树模型（ADR-0001）：回复根评论 root=parent=根评论 id；回复子回复 root=楼根、parent=被回复子回复。
        // 归属一律由服务端推导，客户端无从伪造
        String rootId = targetEntity.isRoot() ? targetEntity.getCommentId() : targetEntity.getRootId();
        PostCommentEntity replyEntity = PostCommentEntity.createReply(targetEntity.getPostId(), rootId,
                targetEntity.getCommentId(), userId, content, PostStatusVO.NORMAL);
        publishVisible(replyEntity);
        replyEntity.setAuthorNickname(nicknameOf(userId));
        replyEntity.setReplyToUserId(targetEntity.getUserId());
        replyEntity.setReplyToNickname(targetEntity.getAuthorNickname());
        return replyEntity;
    }
    /** 落库即可见：评论行 + 评论数 +1 + 楼根 reply_count +1 + 计数窄表行 + 热评索引行（根评论）同一事务 */
    private void publishVisible(PostCommentEntity commentEntity) {
        postRepository.saveVisibleCommentWithCount(commentEntity);
    }
    @Override
    public PostCommentEntity getComment(String commentId) {
        return postRepository.findCommentById(commentId);
    }
    @Override
    public List<PostCommentEntity> listReplies(String rootId, String viewerId, int offset, int limit) {
        if (!threadVisible(rootId)) {
            return List.of();
        }
        return postRepository.pageRepliesByRootId(rootId, viewerId, offset, limit);
    }
    @Override
    public long countReplies(String rootId, String viewerId) {
        if (!threadVisible(rootId)) {
            return 0;
        }
        return postRepository.countRepliesForThread(rootId, viewerId);
    }
    /** 删根整楼消失（票 07）：楼根被软删后，楼内子回复随楼一起不可达；其余状态维持原行为 */
    private boolean threadVisible(String rootId) {
        PostCommentEntity rootEntity = postRepository.findCommentById(rootId);
        return rootEntity != null && rootEntity.getStatus() != PostStatusVO.DELETED;
    }
    @Override
    public void deleteComment(String commentId, String currentUserId) {
        PostCommentEntity commentEntity = postRepository.findCommentById(commentId);
        if (commentEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "评论不存在");
        }
        boolean isCommentOwner = commentEntity.getUserId().equals(currentUserId);
        PostEntity postEntity = postRepository.findPostById(commentEntity.getPostId());
        boolean isPostOwner = postEntity != null && postEntity.isOwner(currentUserId);
        if (!isCommentOwner && !isPostOwner) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能删除自己的评论或自己帖子下的评论");
        }
        softDelete(commentEntity);
    }
    @Override
    public void markDeleted(String commentId) {
        PostCommentEntity commentEntity = postRepository.findCommentById(commentId);
        if (commentEntity == null) {
            return; // 幂等：不存在
        }
        softDelete(commentEntity);
    }
    @Override
    public void rebuildCommentFeedIndex() {
        postRepository.rebuildCommentFeedIndex();
    }
    /**
     * 统一软删（用户删除与举报下架同轨，物理 DELETE 废弃）：状态转 DELETED、行保留。
     * 扣减与否由条件 UPDATE 实际命中的前置状态决定（读到的旧态仅用于定位，防并发漂移）；
     * 状态翻转与计数增减在仓储内同一事务
     */
    private void softDelete(PostCommentEntity commentEntity) {
        postRepository.softDeleteCommentWithCount(
                commentEntity.getCommentId(), commentEntity.getPostId(), commentEntity.getRootId());
    }
    @Override
    public PostCommentEntity likeComment(String commentId, String userId) {
        // 仅对可见评论有效：下架一律拒绝
        PostCommentEntity commentEntity = getNormalComment(commentId);
        // 合并写：评论点赞计数落窄表 post_comment_like_count（真源），不是主表列；
        // 返回的是点赞前读到的评论快照（前置检查用），计数由合并器在同一事务推进。
        // 最热档的排序键是根评论索引的 like_count 快照（同一事务旁带推进），同样不是这次返回的实体字段
        LikeStateVO state = postRepository.likeCommentWithMergedCount(commentId, userId);
        if (state == LikeStateVO.DUPLICATE) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "请勿重复点赞");
        }
        return commentEntity;
    }
    @Override
    public void unlikeComment(String commentId, String userId) {
        getNormalComment(commentId);
        postRepository.unlikeCommentWithCount(commentId, userId);
    }
    @Override
    public List<String> listLikedCommentIds(String userId, List<String> commentIds) {
        return postRepository.selectLikedCommentIds(userId, commentIds);
    }
    private PostCommentEntity getNormalComment(String commentId) {
        PostCommentEntity commentEntity = postRepository.findCommentById(commentId);
        if (commentEntity == null || commentEntity.getStatus() != PostStatusVO.NORMAL) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "评论不存在");
        }
        return commentEntity;
    }
    /** 发布响应 DTO 需作者昵称：写入路径无 JOIN，按 id 读用户补齐（只读跨域 port） */
    private String nicknameOf(String userId) {
        PostUserVO user = userQueryPort.findById(userId);
        return user == null ? null : user.getNickname();
    }
    /**
     * 隐私开关「公开我的评论」对他视是否可见：开关关闭且 viewer ≠ owner 时为 false；
     * viewer = owner（本人看自己）恒 true；profile 读不到（用户不存在）按可见处理（列表本就为空）。
     * 开关原始值由 user 只读 port 取回，判据留在本域
     */
    private boolean isCommentsVisibleTo(String ownerId, String viewerId) {
        if (viewerId != null && viewerId.equals(ownerId)) {
            return true;
        }
        PostUserVO owner = userQueryPort.findById(ownerId);
        return owner == null || !Boolean.FALSE.equals(owner.getShowComments());
    }
}
