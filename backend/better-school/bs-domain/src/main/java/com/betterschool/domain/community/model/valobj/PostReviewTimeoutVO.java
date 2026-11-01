package com.betterschool.domain.community.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 待审超时帖子扫描结果：轮次随行带出，供 dedup_key 区分重提轮（撤回/被拒重提后新一轮可再次提醒）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostReviewTimeoutVO {

    private String postId;
    private int reviewRound;
}
