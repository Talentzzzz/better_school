package com.betterschool.infrastructure.adapter.repository.user;

import com.betterschool.domain.search.model.valobj.SearchEntityType;
import com.betterschool.domain.search.model.valobj.SearchSyncMessage;
import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.domain.user.model.valobj.GenderVO;
import com.betterschool.domain.user.model.valobj.UserBriefVO;
import com.betterschool.domain.user.model.valobj.UserRoleVO;
import com.betterschool.domain.user.model.valobj.UserStatusVO;
import com.betterschool.infrastructure.dao.post.IPostDao;
import com.betterschool.infrastructure.dao.post.IPostLikeCountDao;
import com.betterschool.infrastructure.dao.post.po.Post;
import com.betterschool.infrastructure.dao.user.IUserDao;
import com.betterschool.infrastructure.dao.user.po.User;
import com.betterschool.infrastructure.dao.user.po.UserBrief;
import com.betterschool.infrastructure.outbox.OutboxMessageStore;
import com.betterschool.infrastructure.security.JwtService;
import com.betterschool.types.common.Constants;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Repository
@RequiredArgsConstructor
public class UserRepository implements IUserRepository {

    private final IUserDao userDao;
    /** 获赞统计回查帖 id 集（post 单表） */
    private final IPostDao postDao;
    /** 获赞统计回查 like_count 窄表（post_like_count 单表，SQL 层零 JOIN） */
    private final IPostLikeCountDao postLikeCountDao;

    private final RedissonClient redissonClient;

    private final OutboxMessageStore outboxMessageStore;

    private final JwtService jwtService;

    private final TransactionTemplate transactionTemplate;

    @Override
    public UserEntity findByPhone(String phone) {
        User user = userDao.selectByPhone(phone);
        return user == null ? null : toEntity(user);
    }

    @Override
    public UserEntity findById(String userId) {
        User user = userDao.selectById(userId);
        return user == null ? null : toEntity(user);
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
    public List<String> listUserIdsByRole(UserRoleVO role) {
        return userDao.selectIdsByRole(role.name());
    }

    @Override
    public List<UserEntity> listByIds(List<String> userIdList) {
        return userDao.selectByIds(userIdList).stream().map(this::toEntity).toList();
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

    private UserEntity toEntity(User user) {
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
        userEntity.setStatus(UserStatusVO.valueOf(user.getState()));
        userEntity.setCreditScore(user.getCreditScore());
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
        user.setState(userEntity.getStatus().name());
        user.setCreditScore(userEntity.getCreditScore());
        return user;
    }
}
