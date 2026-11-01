package com.betterschool.infrastructure.adapter.repository.user;

import com.betterschool.domain.search.model.valobj.SearchEntityType;
import com.betterschool.domain.search.model.valobj.SearchSyncMessage;
import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.domain.user.model.valobj.GenderVO;
import com.betterschool.domain.user.model.valobj.UserRoleVO;
import com.betterschool.domain.user.model.valobj.UserStatusVO;
import com.betterschool.infrastructure.dao.user.IUserDao;
import com.betterschool.infrastructure.dao.user.po.UserPO;
import com.betterschool.infrastructure.outbox.OutboxMessageStore;
import com.betterschool.infrastructure.security.JwtService;
import com.betterschool.types.common.Constants;
import com.betterschool.types.id.IIdGenerator;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Repository
@RequiredArgsConstructor
public class UserRepository implements IUserRepository {

    private final IUserDao userDao;

    private final RedissonClient redissonClient;

    private final OutboxMessageStore outboxMessageStore;

    private final JwtService jwtService;

    private final TransactionTemplate transactionTemplate;

    private final IIdGenerator idGenerator;

    @Override
    public UserEntity findByPhone(String phone) {
        UserPO userPO = userDao.selectByPhone(phone);
        return userPO == null ? null : toEntity(userPO);
    }

    @Override
    public UserEntity findById(String userId) {
        UserPO userPO = userDao.selectById(userId);
        return userPO == null ? null : toEntity(userPO);
    }

    /** 落库 + 注册消息 + 搜索同步消息一个原子用例（同事务写 outbox） */
    @Override
    public UserEntity saveRegistered(UserEntity userEntity) {
        return transactionTemplate.execute(status -> {
            // 业务 ID 应用层发号、insert 前一次写入（物理自增 id 只作内部键，不再同值回写）
            userEntity.setUserId(idGenerator.nextId());
            UserPO userPO = toPO(userEntity);
            userDao.insert(userPO);
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
        return userDao.sumNormalPostLikeCount(userId);
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
        UserEntity owner = findById(ownerId);
        return owner == null || !Boolean.FALSE.equals(switchGetter.apply(owner));
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
    public List<UserEntity> listByIds(List<String> userIds) {
        return userDao.selectByIds(userIds).stream().map(this::toEntity).toList();
    }

    private UserEntity toEntity(UserPO userPO) {
        UserEntity userEntity = new UserEntity();
        userEntity.setUserId(userPO.getUserId());
        userEntity.setPhone(userPO.getPhone());
        userEntity.setNickname(userPO.getNickname());
        userEntity.setAvatar(userPO.getAvatar());
        userEntity.setSignature(userPO.getSignature());
        userEntity.setGender(userPO.getGender() == null ? null : GenderVO.valueOf(userPO.getGender()));
        userEntity.setShowLiked(userPO.getShowLiked());
        userEntity.setShowComments(userPO.getShowComments());
        userEntity.setRole(UserRoleVO.valueOf(userPO.getRole()));
        userEntity.setStatus(UserStatusVO.valueOf(userPO.getState()));
        userEntity.setCreditScore(userPO.getCreditScore());
        userEntity.setLastLoginAt(userPO.getLastLoginAt());
        userEntity.setCreatedAt(userPO.getCreateTime());
        return userEntity;
    }

    private UserPO toPO(UserEntity userEntity) {
        UserPO userPO = new UserPO();
        userPO.setUserId(userEntity.getUserId());
        userPO.setPhone(userEntity.getPhone());
        userPO.setNickname(userEntity.getNickname());
        userPO.setAvatar(userEntity.getAvatar());
        userPO.setSignature(userEntity.getSignature());
        userPO.setGender(userEntity.getGender() == null ? null : userEntity.getGender().name());
        userPO.setShowLiked(userEntity.getShowLiked());
        userPO.setShowComments(userEntity.getShowComments());
        userPO.setRole(userEntity.getRole().name());
        userPO.setState(userEntity.getStatus().name());
        userPO.setCreditScore(userEntity.getCreditScore());
        userPO.setLastLoginAt(userEntity.getLastLoginAt());
        userPO.setUpdateTime(userEntity.getCreatedAt());
        userPO.setCreateTime(userEntity.getCreatedAt());
        return userPO;
    }
}
