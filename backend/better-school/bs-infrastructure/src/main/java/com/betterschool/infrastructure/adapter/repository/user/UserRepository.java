package com.betterschool.infrastructure.adapter.repository.user;

import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.domain.user.model.valobj.GenderVO;
import com.betterschool.domain.user.model.valobj.UserBriefVO;
import com.betterschool.domain.user.model.valobj.UserRoleVO;
import com.betterschool.domain.user.model.valobj.UserStatusVO;
import com.betterschool.infrastructure.dao.credit.IUserCreditAccountDao;
import com.betterschool.infrastructure.dao.credit.po.UserCredit;
import com.betterschool.infrastructure.dao.post.IPostDao;
import com.betterschool.infrastructure.dao.post.IPostLikeCountDao;
import com.betterschool.infrastructure.dao.post.po.Post;
import com.betterschool.infrastructure.dao.user.IUserDao;
import com.betterschool.infrastructure.dao.user.po.User;
import com.betterschool.infrastructure.dao.user.po.UserBrief;
import com.betterschool.infrastructure.outbox.OutboxMessageStore;
import com.betterschool.infrastructure.search.ISearchIndex;
import com.betterschool.infrastructure.search.SearchIndexName;
import com.betterschool.infrastructure.search.UserSearchDoc;
import com.betterschool.infrastructure.security.JwtService;
import com.betterschool.types.common.Constants;
import com.betterschool.types.common.SearchEntityType;
import com.betterschool.types.common.SearchPageVO;
import com.betterschool.types.common.SearchSyncMessage;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class UserRepository implements IUserRepository {

    private static final String DEFAULT_STATE = "NORMAL";
    private static final int DEFAULT_CREDIT_SCORE = 100;

    private final IUserDao userDao;
    /** 获赞统计回查帖 id 集（post 单表） */
    private final IPostDao postDao;
    /** 获赞统计回查 like_count 窄表（post_like_count 单表，SQL 层零 JOIN） */
    private final IPostLikeCountDao postLikeCountDao;
    /** 信用账户只读（user_credit 表，credit 域独占写）：拼装 UserEntity.status/creditScore，读跨域（拆库后走 credit 域 API） */
    private final IUserCreditAccountDao userCreditAccountDao;

    private final RedissonClient redissonClient;

    private final OutboxMessageStore outboxMessageStore;

    private final JwtService jwtService;

    /** 倒排索引技术组件（技术能力：本域仓储直接用，不为它开 domain port） */
    private final ISearchIndex searchIndex;

    private final TransactionTemplate transactionTemplate;

    @Override
    public UserEntity findByPhone(String phone) {
        User user = userDao.selectByPhone(phone);
        return user == null ? null : toEntity(user, loadCredit(user.getUserId()));
    }

    @Override
    public UserEntity findById(String userId) {
        User user = userDao.selectById(userId);
        return user == null ? null : toEntity(user, loadCredit(userId));
    }

    /** 落库 + 注册消息 + 搜索同步消息一个原子用例（同事务写 outbox） */
    @Override
    public UserEntity saveRegistered(UserEntity userEntity) {
        // 业务 ID 应用层发号、insert 前一次写入（物理自增 id 只作内部键，不再同值回写）
        userEntity.setUserId(UUID.randomUUID().toString());
        User user = toPO(userEntity);
        return transactionTemplate.execute(status -> {
            userDao.insert(user);
            outboxMessageStore.save(Constants.USER_REGISTER_TOPIC, userEntity);
            // 注册后昵称参与用户搜索，落搜索同步消息（与入库同事务）
            outboxMessageStore.save(Constants.SEARCH_SYNC_TOPIC,
                    new SearchSyncMessage(SearchEntityType.USER.name().toLowerCase(), userEntity.getUserId()));
            return userEntity;
        });
    }

    @Override
    public void update(UserEntity userEntity) {
        userDao.updateById(toPO(userEntity));
    }

    @Override
    public void updateProfile(String userId, String signature, String gender) {
        userDao.updateProfile(userId, signature, gender);
    }

    @Override
    public void updatePrivacy(String userId, boolean showLiked, boolean showComments) {
        userDao.updatePrivacy(userId, showLiked, showComments);
    }

    @Override
    public long sumNormalPostLikeCount(String userId) {
        // 两段查拼装（原 post ⋈ post_like_count 的 SUM）：①post 单表取 NORMAL 帖 id 集；
        // ②like_count 窄表批量读，Java 求和（缺行 = 计数 0，与原 INNER JOIN 行集口径一致）
        List<String> postIdList = postDao.selectNormalIdsByUser(userId);
        if (postIdList.isEmpty()) {
            return 0;
        }
        long total = 0;
        for (Post countRow : postLikeCountDao.selectLikeCountsByPostIds(postIdList)) {
            total += countRow.getLikeCount() == null ? 0 : countRow.getLikeCount();
        }
        return total;
    }

    @Override
    public boolean isLikedVisibleTo(String ownerId, String viewerId) {
        return isSwitchVisibleTo(ownerId, viewerId, UserEntity::getShowLiked);
    }

    @Override
    public boolean isCommentsVisibleTo(String ownerId, String viewerId) {
        return isSwitchVisibleTo(ownerId, viewerId, UserEntity::getShowComments);
    }

    /** 开关仅他视生效：viewer = owner 恒可见；owner 不存在按可见处理（列表本就为空） */
    private boolean isSwitchVisibleTo(String ownerId, String viewerId, Function<UserEntity, Boolean> switchGetter) {
        if (viewerId != null && viewerId.equals(ownerId)) {
            return true;
        }
        UserEntity ownerEntity = findById(ownerId);
        return ownerEntity == null || !Boolean.FALSE.equals(switchGetter.apply(ownerEntity));
    }

    @Override
    public void storeVerifyCode(String phone, String code, long ttlSeconds) {
        redissonClient.getBucket(Constants.VERIFY_CODE_KEY + phone)
                .set(code, ttlSeconds, TimeUnit.SECONDS);
    }

    @Override
    public boolean verifyVerifyCode(String phone, String code) {
        RBucket<String> bucket = redissonClient.getBucket(Constants.VERIFY_CODE_KEY + phone);
        // getAndDelete 原子取出并删除，验证码一次性生效；过期由 Redis TTL 处理
        String storedCode = bucket.getAndDelete();
        return storedCode != null && storedCode.equals(code);
    }

    @Override
    public String generateToken(String userId, String role) {
        return jwtService.generateToken(userId, role);
    }

    @Override
    public List<String> listAllUserIds() {
        return userDao.selectAllIds();
    }

    @Override
    public List<String> listUserIdsAfter(String lastUserId, int limit) {
        return userDao.selectIdsAfter(lastUserId, limit);
    }

    @Override
    public SearchPageVO<String> searchUserIds(String keyword, int page, int pageSize) {
        // ES 侧 status 过滤只是冗余兜底（索引可能短暂滞后），最终公开判定在领域服务回库后做
        return searchIndex.search(SearchIndexName.USER, SearchIndexName.USER_SEARCH_FIELDS,
                List.of(UserStatusVO.NORMAL.name()), keyword, page, pageSize);
    }

    @Override
    public void syncUserSearchDoc(String userId) {
        UserEntity userEntity = findById(userId);
        if (userEntity != null && userEntity.getStatus() == UserStatusVO.NORMAL) {
            searchIndex.bulkUpsert(SearchIndexName.USER, List.of(toUserSearchDoc(userEntity)),
                    UserSearchDoc::getUserId);
        } else {
            searchIndex.deleteDoc(SearchIndexName.USER, userId);
        }
    }

    @Override
    public int rebuildUserSearchIndex() {
        searchIndex.recreate(SearchIndexName.USER);
        int total = 0;
        String cursor = "";
        while (true) {
            List<String> idList = listUserIdsAfter(cursor, ISearchIndex.DEFAULT_REBUILD_BATCH_SIZE);
            if (idList.isEmpty()) {
                break;
            }
            // 批内一次 IN 回查 + Java 侧过滤，游标推进到空批是唯一终止条件
            List<UserSearchDoc> docList = listByIds(idList).stream()
                    .filter(user -> user.getStatus() == UserStatusVO.NORMAL)
                    .map(this::toUserSearchDoc)
                    .toList();
            searchIndex.bulkUpsert(SearchIndexName.USER, docList, UserSearchDoc::getUserId);
            total += docList.size();
            cursor = idList.get(idList.size() - 1);
        }
        return total;
    }

    /** 实体 → 索引文档：用户只有昵称可搜，头像/简介等展示字段不进索引 */
    private UserSearchDoc toUserSearchDoc(UserEntity userEntity) {
        UserSearchDoc doc = new UserSearchDoc();
        doc.setUserId(userEntity.getUserId());
        doc.setNickname(userEntity.getNickname());
        doc.setStatus(userEntity.getStatus().name());
        return doc;
    }

    @Override
    public List<String> listUserIdsByRole(UserRoleVO role) {
        return userDao.selectIdsByRole(role.name());
    }

    @Override
    public List<UserEntity> listByIds(List<String> userIdList) {
        Map<String, UserCredit> creditMap = loadCredits(userIdList);
        return userDao.selectByIds(userIdList).stream()
                .map(user -> toEntity(user, creditMap.get(user.getUserId())))
                .toList();
    }

    @Override
    public List<UserBriefVO> listBriefByIds(List<String> userIdList) {
        return userDao.selectBriefByIds(userIdList).stream().map(this::toBriefVO).toList();
    }

    private UserBriefVO toBriefVO(UserBrief userBrief) {
        UserBriefVO userBriefVO = new UserBriefVO();
        userBriefVO.setUserId(userBrief.getUserId());
        userBriefVO.setNickname(userBrief.getNickname());
        userBriefVO.setAvatar(userBrief.getAvatar());
        return userBriefVO;
    }

    /** 单个用户信用账户只读（user_credit 无行 = 默认 NORMAL/100，懒播种语义） */
    private UserCredit loadCredit(String userId) {
        return userCreditAccountDao.selectByUserId(userId);
    }

    /** 批量信用账户只读（列表场景一次取回，避免逐行查询） */
    private Map<String, UserCredit> loadCredits(List<String> userIdList) {
        if (userIdList.isEmpty()) {
            return Map.of();
        }
        return userCreditAccountDao.selectByUserIds(userIdList).stream()
                .collect(Collectors.toMap(UserCredit::getUserId, Function.identity()));
    }

    private UserEntity toEntity(User user, UserCredit credit) {
        UserEntity userEntity = new UserEntity();
        userEntity.setUserId(user.getUserId());
        userEntity.setPhone(user.getPhone());
        userEntity.setNickname(user.getNickname());
        userEntity.setAvatar(user.getAvatar());
        userEntity.setSignature(user.getSignature());
        userEntity.setGender(user.getGender() == null ? null : GenderVO.valueOf(user.getGender()));
        userEntity.setShowLiked(user.getShowLiked());
        userEntity.setShowComments(user.getShowComments());
        userEntity.setRole(UserRoleVO.valueOf(user.getRole()));
        userEntity.setStatus(UserStatusVO.valueOf(credit == null || credit.getState() == null
                ? DEFAULT_STATE : credit.getState()));
        userEntity.setCreditScore(credit == null || credit.getCreditScore() == null
                ? DEFAULT_CREDIT_SCORE : credit.getCreditScore());
        userEntity.setLastLoginAt(user.getLastLoginAt());
        userEntity.setCreatedAt(user.getCreateTime());
        return userEntity;
    }

    private User toPO(UserEntity userEntity) {
        User user = new User();
        user.setUserId(userEntity.getUserId());
        user.setPhone(userEntity.getPhone());
        user.setNickname(userEntity.getNickname());
        user.setAvatar(userEntity.getAvatar());
        user.setSignature(userEntity.getSignature());
        user.setGender(userEntity.getGender() == null ? null : userEntity.getGender().name());
        user.setShowLiked(userEntity.getShowLiked());
        user.setShowComments(userEntity.getShowComments());
        user.setRole(userEntity.getRole().name());
        return user;
    }
}
