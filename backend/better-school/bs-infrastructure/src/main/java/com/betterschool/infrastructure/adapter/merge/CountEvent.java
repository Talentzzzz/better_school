package com.betterschool.infrastructure.adapter.merge;

import java.util.Objects;

/**
 * 一件计数写事件：动作 + 目标行 + 谁干的。
 *
 * <p>{@code targetId} 是<b>业务行主键</b>（帖子 / 评论 / 商品 / 跑腿）；分片键由调用方另外显式给
 * （{@code submitAndWait(shardKey, event)}），合并器不从 payload 推导 key——否则将来网关按 key
 * 分流时两层路由会分叉（spec §2.8）。
 *
 * <p><b>业务行主键是字符串</b>（uuid-biz-id 票 05）：帖/评/商品/跑腿的业务 id 统一是 UUID 字符串。
 * 分片键与分组键必须是<b>原值本身</b>而不是它的哈希——分组要求「同一行」严格相等，哈希碰撞会把两行
 * 合成一组（一组一条 UPDATE 就会写错行）；只有分片下标那一步才允许拿哈希折位。
 *
 * @param action   动作类型
 * @param targetId 目标行业务主键（postId / commentId / productId / errandOrderId）
 * @param userId   操作人；浏览类必须给（UV 口径）
 */
public final class CountEvent {

    private final CountAction action;
    private final String targetId;
    private final String userId;

    public CountEvent(CountAction action, String targetId, String userId) {
        this.action = action;
        this.targetId = targetId;
        this.userId = userId;
    }

    public CountAction action() {
        return action;
    }

    public String targetId() {
        return targetId;
    }

    public String userId() {
        return userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CountEvent other)) return false;
        return Objects.equals(action, other.action)
                && Objects.equals(targetId, other.targetId)
                && Objects.equals(userId, other.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, targetId, userId);
    }

    @Override
    public String toString() {
        return "CountEvent[action=" + action + ", targetId=" + targetId + ", userId=" + userId + "]";
    }
}
