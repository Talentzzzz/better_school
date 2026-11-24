package com.betterschool.usecase.search;

import com.betterschool.domain.community.service.post.ICommunityPostService;
import com.betterschool.domain.idle.service.IIdleService;
import com.betterschool.domain.user.service.profile.IUserProfileService;
import com.betterschool.types.common.SearchEntityType;
import com.betterschool.types.common.SearchSyncMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 搜索索引增量同步用例：一条 MQ 消息按 type 分派给实体所属域的领域服务。
 *
 * <p>为什么必须有这个编排点：一个 topic（bs-search-sync）承载三种实体，分发本身是跨域动作。
 * 它不能下落 infrastructure（技术包不得反向依赖仓储），也不该让三个域各建一个消费者抢同一 topic。
 *
 * <p>依赖只到域服务：索引同步是各域自己的能力（域服务 → 本域仓储），
 * 用例层只负责「这条消息归谁」，不绕过服务直接碰仓储。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchSyncCase {

    private final ICommunityPostService communityPostService;
    private final IIdleService idleService;
    private final IUserProfileService userProfileService;

    public void sync(SearchSyncMessage message) {
        SearchEntityType entityType = parseType(message.getType());
        if (entityType == null) {
            log.warn("[SearchSyncCase] 未知实体类型 type={}, id={}", message.getType(), message.getId());
            return;
        }
        switch (entityType) {
            case POST -> communityPostService.syncSearchDoc(message.getId());
            case PRODUCT -> idleService.syncSearchDoc(message.getId());
            case USER -> userProfileService.syncSearchDoc(message.getId());
        }
    }

    private SearchEntityType parseType(String type) {
        if (type == null) {
            return null;
        }
        try {
            return SearchEntityType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
