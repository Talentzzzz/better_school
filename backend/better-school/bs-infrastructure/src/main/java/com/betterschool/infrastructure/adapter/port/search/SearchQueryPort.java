package com.betterschool.infrastructure.adapter.port.search;

import com.betterschool.domain.community.adapter.repository.ICommunityRepository;
import com.betterschool.domain.community.model.entity.PostEntity;
import com.betterschool.domain.search.adapter.port.ISearchQueryPort;
import com.betterschool.domain.idle.adapter.repository.IIdleRepository;
import com.betterschool.domain.idle.model.entity.ProductEntity;
import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.domain.user.model.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 搜索同步只读查询端口实现：只读 community / idle / user 三域的读路径（含各自缓存口径），
 * 只把实体取回来交 search 判定「进不进索引」。
 * 落位在 adapter/port（不是 repository），跨域只读边的伸手点集中在这里
 */
@Component
@RequiredArgsConstructor
public class SearchQueryPort implements ISearchQueryPort {

    private final ICommunityRepository communityRepository;
    private final IIdleRepository idleRepository;
    private final IUserRepository userRepository;

    @Override
    public PostEntity findPost(String postId) {
        return communityRepository.findPostById(postId);
    }

    @Override
    public List<PostEntity> listPostsByIds(List<String> postIds) {
        return communityRepository.listByIds(postIds);
    }

    @Override
    public List<String> listPostIdsAfter(String lastPostId, int limit) {
        return communityRepository.listIdsAfter(lastPostId, limit);
    }

    @Override
    public ProductEntity findProduct(String productId) {
        return idleRepository.findById(productId);
    }

    @Override
    public List<ProductEntity> listProductsByIds(List<String> productIds) {
        return idleRepository.listByIds(productIds);
    }

    @Override
    public List<String> listProductIdsAfter(String lastProductId, int limit) {
        return idleRepository.listIdsAfter(lastProductId, limit);
    }

    @Override
    public UserEntity findUser(String userId) {
        return userRepository.findById(userId);
    }

    @Override
    public List<UserEntity> listUsersByIds(List<String> userIds) {
        return userRepository.listByIds(userIds);
    }

    @Override
    public List<String> listUserIdsAfter(String lastUserId, int limit) {
        return userRepository.listUserIdsAfter(lastUserId, limit);
    }
}
