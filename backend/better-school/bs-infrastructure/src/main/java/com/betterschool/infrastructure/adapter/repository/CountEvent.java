package com.betterschool.infrastructure.adapter.repository;

import java.util.Objects;

/**
 * 一件计数写事件：动作 + 目标行 + 谁干的。
 *
 * <p>{@code targetId} 是<b>业务行主键</b>（帖子 / 评论 / 商品）。分组键由使用方的
 * {@code MergeStrategy} 决定：计数写入一律是「动作 × 行主键」——同一个帖子上的点赞与浏览必须
 * 分成两组（打不同的表），同动作不同行也必须分开（一组一条 UPDATE 只该命中一行）。
 *
 * <p><b>业务行主键是字符串</b>（uuid-biz-id 票 05）：帖/评/商品的业务 id 统一是 UUID 字符串。
 * 分组键必须是<b>原值本身</b>而不是它的哈希——分组要求「同一行」严格相等，哈希碰撞会把两行
 * 合成一组（一组一条 UPDATE 就会写错行）。
 *
 * @param action   动作类型
 * @param targetId 目标行业务主键（postId / commentId / productId）
 * @param userId   操作人；浏览类必须给（UV 口径）
 */
public final class CountEvent {

    private final CountActionEnum action;
    private final String targetId;
    private final String userId;

    public CountEvent(CountActionEnum action, String targetId, String userId) {
        this.action = action;
        this.targetId = targetId;
        this.userId = userId;
    }

    public CountActionEnum action() {
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
        if (!(o instanceof CountEvent countEvent)) return false;
        return Objects.equals(action, countEvent.action)
                && Objects.equals(targetId, countEvent.targetId)
                && Objects.equals(userId, countEvent.userId);
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
