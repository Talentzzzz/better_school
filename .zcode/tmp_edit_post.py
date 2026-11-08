import os

def edit(p, pairs):
    s = open(p, encoding='utf-8').read()
    for old, new in pairs:
        assert old in s, (p, old[:80])
        s = s.replace(old, new)
    open(p, 'w', encoding='utf-8', newline='').write(s)
    print("ok", p)

# ---- ICommunityPostService ----
edit('bs-domain/src/main/java/com/betterschool/domain/community/service/post/ICommunityPostService.java', [
("""import com.betterschool.domain.community.model.entity.PostEntity;
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
""",
"""import com.betterschool.domain.community.model.entity.PostEntity;
import com.betterschool.domain.community.model.valobj.FeedPageVO;

import java.util.List;

/**
 * 社区帖子领域服务：发布/列表/详情/删除/点赞状态流转
 */
public interface ICommunityPostService {

    /** 发布（落库即可见；信用分门槛由 case 层前置校验） */
    String publishPost(String userId, String title, String content, List<String> images, String cover);

    /** 保存草稿（私有停靠：字段可缺省，不做信用分门槛，由 case 层声明） */
    String savePostDraft(String userId, String title, String content, List<String> images, String cover);

    /** 编辑草稿（仅 DRAFT 可编辑，全量覆盖；归属与状态守卫在本方法内） */
    void updatePostDraft(String userId, String postId, String title, String content, List<String> images,
                         String cover);

    /** 取本人草稿（归属 + DRAFT 状态守卫；提交前校验用） */
    PostEntity getOwnedPostDraft(String userId, String postId);

    /** 提交草稿（完整性由 case 层前置；通过即发布可见） */
    void submitPostDraft(String userId, String postId);
"""),
("    /** 我的帖子（作者视角）：NORMAL/PENDING_REVIEW/DRAFT/REJECTED，合并点赞+浏览数 */",
 "    /** 我的帖子（作者视角）：NORMAL/DRAFT，合并点赞+浏览数 */"),
("    /** 详情：浏览计数 +1，登录时返回是否已赞；待审核/已驳回帖仅作者本人可见；浏览量仅作者本人合并返回 */",
 "    /** 详情：浏览计数 +1，登录时返回是否已赞；草稿帖仅作者本人可见；浏览量仅作者本人合并返回 */"),
("""    /** 读单条帖（无副作用不计数，任何状态可读；审核消费者/通知场景用） */
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

    /** feed 索引全量重建""",
"""    /** 读单条帖（无副作用不计数，任何状态可读；通知场景用） */
    PostEntity getPost(String postId);

    /** 删除（作者可删自己 NORMAL/DRAFT 帖） */
    void deletePost(String postId, String currentUserId);

    /** feed 索引全量重建"""),
("    /** 我的浏览历史（最近浏览倒序；已删/被拒的他人帖过滤，本人浏览过的待审/驳回/草稿帖照常展示） */",
 "    /** 我的浏览历史（最近浏览倒序；已删的他人帖过滤，本人浏览过的草稿帖照常展示） */"),
])

# ---- PostServiceImpl ----
edit('bs-domain/src/main/java/com/betterschool/domain/community/service/post/PostServiceImpl.java', [
("""import com.betterschool.domain.community.model.valobj.LikeStateVO;
import com.betterschool.domain.community.model.valobj.PostReviewTimeoutVO;
import com.betterschool.domain.community.model.valobj.PostStatusVO;""",
"""import com.betterschool.domain.community.model.valobj.LikeStateVO;
import com.betterschool.domain.community.model.valobj.PostStatusVO;"""),
("""    private static final int MAX_PAGE_SIZE = 50;
    private static final int SCAN_BATCH_SIZE = 100;
    private static final int REVIEW_REMINDER_HOURS = 24;
""",
"""    private static final int MAX_PAGE_SIZE = 50;
"""),
("""    public String publishPost(String userId, String title, String content, List<String> images,
                              String cover) {
        // 信用分门槛与同步初筛由 case 层前置校验；先审后发：落库即待审，审核通过时才落搜索同步消息
        PostEntity postEntity = PostEntity.create(userId, title, content, images, cover);
        communityRepository.save(postEntity);
        return postEntity.getPostId();
    }""",
"""    public String publishPost(String userId, String title, String content, List<String> images,
                              String cover) {
        // 信用分门槛由 case 层前置校验；落库即可见，搜索同步消息同事务落 outbox
        PostEntity postEntity = PostEntity.create(userId, title, content, images, cover);
        communityRepository.saveWithSearchSync(postEntity);
        return postEntity.getPostId();
    }"""),
("        // 草稿为私有停靠内容：不做初筛、不做信用分门槛（提交时才校验），字段可缺省",
 "        // 草稿为私有停靠内容：不做信用分门槛（提交时才校验），字段可缺省"),
("""        if (postEntity.getStatus() != PostStatusVO.DRAFT) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "仅草稿可编辑，待审中的帖子可先撤回再修改");
        }
        return postEntity;
    }

    @Override
    public void submitPostDraft(String userId, String postId) {
        // 完整性校验与同步初筛由 case 层前置
        getOwnedPostDraft(userId, postId);
        if (!communityRepository.submitDraft(postId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "草稿状态已变化，请刷新后重试");
        }
    }

    @Override
    public void withdrawPost(String userId, String postId) {
        PostEntity postEntity = communityRepository.findPostById(postId);
        if (postEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "帖子不存在");
        }
        if (!postEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能操作自己发布的帖子");
        }
        if (postEntity.getStatus() != PostStatusVO.PENDING_REVIEW) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "仅待审中的帖子可撤回");
        }
        if (!communityRepository.withdraw(postId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "帖子状态已变化，请刷新后重试");
        }
    }

    @Override
    public void moveRejectedPostToDraft(String userId, String postId) {
        PostEntity postEntity = communityRepository.findPostById(postId);
        if (postEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "帖子不存在");
        }
        if (!postEntity.isOwner(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能操作自己发布的帖子");
        }
        if (postEntity.getStatus() != PostStatusVO.REJECTED) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "仅被拒绝的帖子可转入草稿箱");
        }
        if (!communityRepository.rejectToDraft(postId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "帖子状态已变化，请刷新后重试");
        }
    }
""",
"""        if (postEntity.getStatus() != PostStatusVO.DRAFT) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "仅草稿可编辑");
        }
        return postEntity;
    }

    @Override
    public void submitPostDraft(String userId, String postId) {
        // 完整性校验由 case 层前置
        getOwnedPostDraft(userId, postId);
        if (!communityRepository.submitDraftWithSearchSync(postId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "草稿状态已变化，请刷新后重试");
        }
    }
"""),
("""    @Override
    public void approvePost(String postId) {
        // 条件 UPDATE 先行，0 行时终态判定（并发双审/重试）
        if (!communityRepository.approveWithSearchSync(postId)) {
            PostEntity currentEntity = communityRepository.findPostById(postId);
            if (currentEntity == null || currentEntity.getStatus() == PostStatusVO.DELETED) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "帖子不存在");
            }
            if (currentEntity.getStatus() == PostStatusVO.NORMAL) {
                return; // 幂等：已通过
            }
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "帖子已被驳回或审核状态已变化");
        }
    }

    @Override
    public void rejectPost(String postId) {
        if (!communityRepository.rejectWithSearchSync(postId)) {
            PostEntity currentEntity = communityRepository.findPostById(postId);
            if (currentEntity == null || currentEntity.getStatus() == PostStatusVO.DELETED) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "帖子不存在");
            }
            if (currentEntity.getStatus() == PostStatusVO.REJECTED) {
                return; // 幂等：已驳回
            }
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "帖子已通过或审核状态已变化，无法驳回");
        }
    }

    @Override
    public void forceDeletePost(String postId) {
        PostEntity postEntity = communityRepository.findPostById(postId);
        if (postEntity == null) {
            return; // 幂等：已下架或不存在
        }
        communityRepository.forceDeleteWithSearchSync(postId);
    }

    @Override
    public List<PostReviewTimeoutVO> listReviewTimeoutPosts() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(REVIEW_REMINDER_HOURS);
        return communityRepository.listReviewTimeoutPosts(cutoff, SCAN_BATCH_SIZE);
    }

    @Override
    public void rebuildFeedIndex() {""",
"""    @Override
    public void rebuildFeedIndex() {"""),
("    /** 可见性：NORMAL 全员可见；PENDING_REVIEW/REJECTED 仅作者本人可见（待审/驳回帖不公开） */",
 "    /** 可见性：NORMAL 全员可见；草稿帖仅作者本人可见（不公开） */"),
])

os.remove('bs-domain/src/main/java/com/betterschool/domain/community/model/valobj/PostReviewTimeoutVO.java')
print("valobj removed")
