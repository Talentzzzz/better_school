package com.betterschool.domain.post.service.post;

import com.betterschool.domain.post.adapter.port.IUserQueryPort;
import com.betterschool.domain.post.adapter.repository.IPostRepository;
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
}
