package com.betterschool.domain.post.service.post;

import com.betterschool.domain.post.adapter.repository.IPostRepository;
import com.betterschool.domain.post.model.entity.PostCommentEntity;
import com.betterschool.domain.post.model.entity.PostEntity;
import com.betterschool.domain.post.model.entity.PostRebateClaimTaskEntity;
import com.betterschool.domain.post.model.valobj.FeedCursorVO;
import com.betterschool.domain.post.model.valobj.FeedIndexItemVO;
import com.betterschool.domain.post.model.valobj.FeedPageVO;
import com.betterschool.domain.post.model.valobj.PostLikeStateEnum;
import com.betterschool.domain.post.model.valobj.PostPublishResultVO;
import com.betterschool.domain.post.model.valobj.PostStatusEnum;
import com.betterschool.types.common.SearchPageVO;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
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

    /** 领取任务重放退避档：第 retryCount+1 次尝试的等待时长（封顶最后一档） */
    private static final List<Duration> REBATE_CLAIM_BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4),
            Duration.ofMinutes(8), Duration.ofMinutes(16));

    private final IPostRepository postRepository;

    @Override
    public PostPublishResultVO publishPost(String userId, String title, String content, List<String> images,
                                           String cover) {
        // 信用分门槛由 case 层前置校验；落库即可见。事务内落领取任务行（PENDING，不发送），
        // 提交后只同步发送搜索同步消息；返回的发布时间是数据库时钟的落库时刻，供返利领取定周期
        PostEntity postEntity = PostEntity.create(userId, title, content, images, cover);
        return postRepository.savePublished(postEntity);
    }

    @Override
    public String savePostDraft(String userId, String title, String content, List<String> images,
                                String cover) {
        // 草稿为私有停靠内容：不做信用分门槛（提交时才校验），字段可缺省
        PostEntity postEntity = PostEntity.createDraft(userId, title, content, images, cover);
        postRepository.saveDraft(postEntity);
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
        PostEntity postEntity = postRepository.getPostEntityById(postId);
        if (postEntity == null) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "草稿不存在");
        }
        if (!postEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "只能操作自己发布的帖子");
        }
        if (postEntity.getStatus() != PostStatusEnum.DRAFT) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "仅草稿可编辑");
        }
        return postEntity;
    }

    @Override
    public PostPublishResultVO submitPostDraft(String userId, String postId) {
        // 归属/状态守卫 + 发布不变式（正文必填）都在域内裁决；仓储的条件 UPDATE 按同一不变式兜底并发竞态
        PostEntity postEntity = getOwnedPostDraft(userId, postId);
        postEntity.ensurePublishable();
        PostPublishResultVO postPublishResultVO = postRepository.submitDraftAsPublished(userId, postId);
        if (postPublishResultVO == null) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "草稿状态已变化，请刷新后重试");
        }
        return postPublishResultVO;
    }

    @Override
    public FeedPageVO listPosts(String sort, String cursor, int page, int pageSize) {
        String sortType = "hot".equals(sort) ? "hot" : "time";
        FeedCursorVO feedCursorVO = cursor == null
                ? FeedCursorVO.firstPage(sortType)
                : FeedCursorVO.decode(cursor, sortType);
        try {
            return pageByFeedIndex(sortType, feedCursorVO, page, pageSize);
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
    private FeedPageVO pageByFeedIndex(String sortType, FeedCursorVO feedCursorVO, int page, int pageSize) {
        List<FeedIndexItemVO> rows = postRepository.scanFeedIndex(sortType, feedCursorVO, pageSize + 1);
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
        return postRepository.pagePublicLikedByUser(userId, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countUserLikedPosts(String userId, String viewerId) {
        return postRepository.countPublicLikedByUser(userId);
    }

    @Override
    public PostEntity postDetail(String postId, String currentUserId) {
        PostEntity postEntity = getVisiblePostEntity(postId, currentUserId);
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
    public PostEntity getPostEntity(String postId) {
        return postRepository.getPostEntityById(postId);
    }

    @Override
    public void deletePostEntity(String postId, String currentUserId) {
        PostEntity postEntity = getVisiblePostEntity(postId, currentUserId);
        if (!postEntity.isOwner(currentUserId)) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "只能删除自己发布的帖子");
        }
        postRepository.deletePostEntityWithSearchSync(postId);
    }

    @Override
    public void rebuildCommentFeedIndex() {
        postRepository.rebuildCommentFeedIndex();
    }

    @Override
    public void markRebateClaimTaskDone(String postId) {
        // 尽力而为：失败只告警，任务留 PENDING 由 postRebateClaimScan 重放（重放按 ref 幂等，不会重复发）
        try {
            postRepository.markRebateClaimTaskDone(postId);
        } catch (Exception e) {
            log.warn("标记发帖返利领取任务完成失败，留待 postRebateClaimScan 重放，postId={}", postId, e);
        }
    }

    @Override
    public List<PostRebateClaimTaskEntity> listDueRebateClaimTasks(int limit) {
        return postRepository.listDueRebateClaimTasks(limit);
    }

    @Override
    public boolean claimRebateClaimTask(PostRebateClaimTaskEntity postRebateClaimTaskEntity) {
        return postRepository.claimRebateClaimTask(postRebateClaimTaskEntity.getId(),
                nextRebateClaimBackoffAt(postRebateClaimTaskEntity.getRetryCount()));
    }

    @Override
    public void markRebateClaimTaskDead(Long id) {
        postRepository.markRebateClaimTaskDead(id);
    }

    /** 第 retryCount+1 次尝试的退避时点（封顶最后一档 16 分钟） */
    private LocalDateTime nextRebateClaimBackoffAt(Integer retryCount) {
        int index = Math.min(retryCount == null ? 0 : retryCount, REBATE_CLAIM_BACKOFF.size() - 1);
        return LocalDateTime.now().plus(REBATE_CLAIM_BACKOFF.get(Math.max(index, 0)));
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
        PostEntity postEntity = postRepository.getPostEntityById(postId);
        if (postEntity == null || postEntity.getStatus() != PostStatusEnum.NORMAL) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "帖子不存在");
        }
        // 合并写：窗口合并成一组一事务（唯一键裁决重复 + 计数同事务推进），返回时计数已精确
        PostLikeStateEnum likeStateVO = postRepository.likePostWithMergedCount(postId, userId);
        if (likeStateVO == PostLikeStateEnum.DUPLICATE) {
            // 重复赞是业务结果不是异常：唯一键裁决出来的「已经赞过」，对外语义与改造前一致
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "请勿重复点赞");
        }
        // 自赞不产生互动通知：发 MQ 前就拦掉，不落 outbox、不占 broker 消息
        if (postEntity.isOwner(userId)) {
            return;
        }
        // 只发「谁赞了谁」的事实；通知内容由消费端（PostCase#notifyPostLiked）决定
        postRepository.publishPostLiked(postId, userId, postEntity.getUserId());
    }

    @Override
    public void unlikePost(String postId, String userId) {
        PostEntity postEntity = postRepository.getPostEntityById(postId);
        if (postEntity == null || postEntity.getStatus() != PostStatusEnum.NORMAL) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "帖子不存在");
        }
        if (postRepository.unlikePostWithMergedCount(postId, userId) == PostLikeStateEnum.NOT_FOUND) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "尚未点赞");
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
                .filter(post -> post.getStatus() == PostStatusEnum.NORMAL)
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

    /** 可见性：NORMAL 全员可见；草稿帖仅作者本人可见（不公开） */
    private PostEntity getVisiblePostEntity(String postId, String currentUserId) {
        PostEntity postEntity = postRepository.getPostEntityById(postId);
        if (postEntity == null) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "帖子不存在");
        }
        if (postEntity.getStatus() == PostStatusEnum.NORMAL || postEntity.isOwner(currentUserId)) {
            return postEntity;
        }
        throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "帖子不存在");
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
        return postRepository.pageCommentsByUser(userId, (page - 1) * pageSize, pageSize);
    }
    @Override
    public long countCommentsByUser(String userId, String viewerId) {
        return postRepository.countCommentsByUser(userId);
    }
    @Override
    public PostCommentEntity publishRootComment(String postId, String userId, String content) {
        PostEntity postEntity = postRepository.getPostEntityById(postId);
        if (postEntity == null || postEntity.getStatus() != PostStatusEnum.NORMAL) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "帖子不存在");
        }
        PostCommentEntity postCommentEntity = PostCommentEntity.create(postId, userId, content, PostStatusEnum.NORMAL);
        postRepository.publishCommentAndPush(postCommentEntity);
        postCommentEntity.setReplyCount(0L);
        return postCommentEntity;
    }
    @Override
    public PostCommentEntity publishReplyComment(String targetCommentId, String userId, String content) {
        PostCommentEntity targetEntity = postRepository.getCommentEntityById(targetCommentId);
        // 下架评论对所有人不可见，视同不存在
        if (targetEntity == null || targetEntity.getStatus() == PostStatusEnum.DELETED) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "评论不存在");
        }
        // 树模型（ADR-0001）：回复根评论 root=parent=根评论 id；回复子回复 root=楼根、parent=被回复子回复。
        // 归属一律由服务端推导，客户端无从伪造
        String rootId = targetEntity.isRoot() ? targetEntity.getCommentId() : targetEntity.getRootId();
        PostCommentEntity replyEntity = PostCommentEntity.createReply(targetEntity.getPostId(), rootId,
                targetEntity.getCommentId(), userId, content, PostStatusEnum.NORMAL);
        postRepository.publishCommentAndPush(replyEntity);
        replyEntity.setReplyToUserId(targetEntity.getUserId());
        replyEntity.setReplyToNickname(targetEntity.getAuthorNickname());
        return replyEntity;
    }
    @Override
    public PostCommentEntity getCommentEntity(String commentId) {
        return postRepository.getCommentEntityById(commentId);
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
        PostCommentEntity postCommentEntity = postRepository.getCommentEntityById(rootId);
        return postCommentEntity != null && postCommentEntity.getStatus() != PostStatusEnum.DELETED;
    }
    @Override
    public void deleteCommentEntity(String commentId, String currentUserId) {
        PostCommentEntity postCommentEntity = postRepository.getCommentEntityById(commentId);
        if (postCommentEntity == null) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "评论不存在");
        }
        boolean isCommentOwner = postCommentEntity.getUserId().equals(currentUserId);
        PostEntity postEntity = postRepository.getPostEntityById(postCommentEntity.getPostId());
        boolean isPostOwner = postEntity != null && postEntity.isOwner(currentUserId);
        if (!isCommentOwner && !isPostOwner) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "只能删除自己的评论或自己帖子下的评论");
        }
        softDelete(postCommentEntity);
    }
    @Override
    public void markDeleted(String commentId) {
        PostCommentEntity postCommentEntity = postRepository.getCommentEntityById(commentId);
        if (postCommentEntity == null) {
            return; // 幂等：不存在
        }
        softDelete(postCommentEntity);
    }
    @Override
    public void rebuildFeedIndex() {
        postRepository.rebuildFeedIndex();
    }
    /**
     * 统一软删（用户删除与举报下架同轨，物理 DELETE 废弃）：状态转 DELETED、行保留。
     * 扣减与否由条件 UPDATE 实际命中的前置状态决定（读到的旧态仅用于定位，防并发漂移）；
     * 状态翻转与计数增减在仓储内同一事务
     */
    private void softDelete(PostCommentEntity postCommentEntity) {
        postRepository.softDeleteCommentWithCount(
                postCommentEntity.getCommentId(), postCommentEntity.getPostId(), postCommentEntity.getRootId());
    }
    @Override
    public PostCommentEntity likeComment(String commentId, String userId) {
        // 仅对可见评论有效：下架一律拒绝
        PostCommentEntity postCommentEntity = getNormalCommentEntity(commentId);
        // 合并写：评论点赞计数落窄表 post_comment_like_count（真源），不是主表列；
        // 返回的是点赞前读到的评论快照（前置检查用），计数由合并器在同一事务推进。
        // 最热档的排序键是根评论索引的 like_count 快照（同一事务旁带推进），同样不是这次返回的实体字段
        PostLikeStateEnum likeStateVO = postRepository.likeCommentWithMergedCount(commentId, userId);
        if (likeStateVO == PostLikeStateEnum.DUPLICATE) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "请勿重复点赞");
        }
        // 自赞不产生互动通知：发 MQ 前就拦掉，不落 outbox、不占 broker 消息（与帖子点赞一致）
        if (!postCommentEntity.getUserId().equals(userId)) {
            // 只发「谁赞了谁」的事实；通知内容由消费端（PostCase#notifyCommentLiked）决定
            postRepository.publishCommentLiked(commentId, userId, postCommentEntity.getUserId());
        }
        return postCommentEntity;
    }
    @Override
    public void unlikeComment(String commentId, String userId) {
        getNormalCommentEntity(commentId);
        postRepository.unlikeCommentWithCount(commentId, userId);
    }
    @Override
    public List<String> listLikedCommentIds(String userId, List<String> commentIds) {
        return postRepository.selectLikedCommentIds(userId, commentIds);
    }
    private PostCommentEntity getNormalCommentEntity(String commentId) {
        PostCommentEntity postCommentEntity = postRepository.getCommentEntityById(commentId);
        if (postCommentEntity == null || postCommentEntity.getStatus() != PostStatusEnum.NORMAL) {
            throw new BusinessException(ResponseCodeEnum.NOT_FOUND, "评论不存在");
        }
        return postCommentEntity;
    }
}
