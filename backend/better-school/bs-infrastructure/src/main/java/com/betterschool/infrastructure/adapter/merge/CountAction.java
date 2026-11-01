package com.betterschool.infrastructure.adapter.merge;

/**
 * 计数侧的写动作：合并器只按它做<b>二次分组</b>（动作 × 行主键），不解析它的语义。
 *
 * <p>六个动作打的行不一样（{@code spec §1.1}）：前三个与关系行同事务、必须精确；
 * 后三个是 fire-and-forget、允许丢。
 */
public enum CountAction {

    /** 帖子点赞：{@code post_like} 关系行 + {@code post_like_count} 窄表 */
    POST_LIKE,

    /** 帖子取消赞：删关系行 + 窄表回落（{@code GREATEST(0, …)}） */
    POST_UNLIKE,

    /** 评论点赞：{@code post_comment_like} 关系行 + 计数窄表 {@code post_comment_like_count}（票 02 起） */
    COMMENT_LIKE,

    /** 帖子浏览：UV 明细 {@code user_view_history} + 加速列 {@code post_view_count}（一组一个事务三步），允许丢 */
    POST_VIEW,

    /** 商品浏览：UV 明细 {@code product_view_history} + 加速列 {@code product_view_count}（一组一个事务三步，与帖子同构；market-feed-uv 票 02），允许丢 */
    PRODUCT_VIEW,

    /** 跑腿浏览：UV 明细 {@code errand_view_history} + 加速列 {@code errand_view_count}（与商品浏览同构），允许丢 */
    ERRAND_VIEW
}
