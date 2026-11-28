package com.betterschool.usecase.post;

import com.betterschool.domain.post.model.entity.PostCommentEntity;
import com.betterschool.domain.post.model.entity.PostEntity;
import com.betterschool.domain.post.model.entity.PostRebateClaimTaskEntity;
import com.betterschool.domain.rebate.model.valobj.RebateClaimResultVO;
import com.betterschool.types.common.CommentLikedMessage;
import com.betterschool.types.common.PostLikedMessage;
import com.betterschool.types.common.PostRebateCreditMessage;

import java.util.List;

/**
 * 帖子用例（发帖 / 草稿 / 评论 / 点赞 / 发帖返利补偿）的统一入口。
 * <p>
 * 跨域组合只在实现里顺序调用各域服务（ADR-0016，无跨域事务）：
 * 信用分门槛、post 域读写、rebate 域领取、积分 / 抽奖次数入账、通知域尽力而为。
 */
public interface IPostCase {

    /** 直接发布：门槛校验通过后发布，再同步领取返利并返回领取结果；refId 即新 postId */
    RebateClaimResultVO publishPost(String userId, String title, String content,
                                    List<String> images, String cover);

    /** 保存帖子草稿：不做信用分门槛，字段可缺省 */
    String savePostDraft(String userId, String title, String content, List<String> images, String cover);

    /** 编辑帖子草稿：仅 DRAFT 可编辑 */
    void updatePostDraft(String userId, String postId, String title, String content,
                         List<String> images, String cover);

    /** 提交帖子草稿：与直接发布同门槛校验，通过即发布可见，并同步领取返利 */
    RebateClaimResultVO submitPostDraft(String userId, String postId);

    /** 帖子点赞：主链路只提交点赞并发「谁赞了谁」的事实事件（自赞在发 MQ 前拦截），通知由消费端决定 */
    void likePost(String postId, String userId);

    /** 帖子被赞消费入口（PostLikedConsumer）：通知的类型/范围/文案/去重键在这里决定 */
    void notifyPostLiked(PostLikedMessage postLikedMessage);

    /** 评论点赞：主链路只提交点赞并发「谁赞了谁」的事实事件（自赞在发 MQ 前拦截），通知由消费端决定 */
    void likeComment(String commentId, String userId);

    /** 评论被赞消费入口（CommentLikedConsumer）：通知的类型/范围/文案/去重键在这里决定 */
    void notifyCommentLiked(CommentLikedMessage commentLikedMessage);

    /** 取消评论点赞：取消不撤回已发通知 */
    void unlikeComment(String commentId, String userId);

    /** 发布根评论：落库即可见，作者昵称由用例层补齐 */
    PostCommentEntity publishRootComment(String postId, String userId, String content);

    /** 回复评论（楼中楼）：落库即可见，作者昵称由用例层补齐 */
    PostCommentEntity publishReplyComment(String targetCommentId, String userId, String content);

    /** 他人主页「赞过」：隐私开关判定在用例层（user 域），domain 只读 post */
    List<PostEntity> listUserLikedPosts(String ownerId, String viewerId, int page, int pageSize);

    long countUserLikedPosts(String ownerId, String viewerId);

    /** 他人主页「评论」：隐私开关判定在用例层（user 域），domain 只读 post */
    List<PostCommentEntity> listCommentsByUser(String ownerId, String viewerId, int page, int pageSize);

    long countCommentsByUser(String ownerId, String viewerId);

    /** 领取任务重放（{@code postRebateClaimScan} 进程内调用，不经过 MQ）：periodKey 缺失（历史行）按当天兜底 */
    void replayClaim(PostRebateClaimTaskEntity postRebateClaimTaskEntity);

    /**
     * 领取任务收敛扫描（xxl {@code postRebateClaimScan} 调度）：扫到点的 PENDING 行，抢占后进程内重放；
     * 单条失败只处置本行（退避重试 / 耗尽置死信），不中断本轮批次。
     *
     * @return 本轮真正收敛（置 DONE）的任务数
     */
    int compensateDueRebateClaims(int limit);

    /** 返利发放（credit consumer 调用）：按订单行的返利类型与领取快照给对应桶入账后标记 GRANTED */
    void grantPostRebate(PostRebateCreditMessage postRebateCreditMessage);
}
