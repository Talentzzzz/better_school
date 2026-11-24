package com.betterschool.usecase.search;

import com.betterschool.domain.community.service.post.ICommunityPostService;
import com.betterschool.domain.idle.service.IIdleService;
import com.betterschool.domain.user.service.profile.IUserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 搜索索引全量重建用例（兜底恢复手段）：依次驱动三个域的领域服务各自重建自己的索引。
 *
 * <p>跨域编排落在这里而不是某个域或技术包——「重建哪几类、什么顺序」是全局视角；
 * 每类索引怎么重建是各域自己的能力（域服务 → 本域仓储）。用例层只调域服务，不碰仓储。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchRebuildCase {

    private final ICommunityPostService communityPostService;
    private final IIdleService idleService;
    private final IUserProfileService userProfileService;

    /** 三类索引依次重建，返回各类回填条数（XXL-Job 调度日志直接可见） */
    public String rebuildAll() {
        int postCount = communityPostService.rebuildSearchIndex();
        int productCount = idleService.rebuildSearchIndex();
        int userCount = userProfileService.rebuildSearchIndex();
        log.info("[SearchRebuildCase] 全量重建完成 post={}, product={}, user={}", postCount, productCount, userCount);
        return String.format("post=%d, product=%d, user=%d", postCount, productCount, userCount);
    }
}
