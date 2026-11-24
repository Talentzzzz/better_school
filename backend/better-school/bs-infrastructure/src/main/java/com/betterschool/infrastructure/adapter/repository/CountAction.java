package com.betterschool.infrastructure.adapter.repository;

/**
 * 计数写入的写动作：通用合并引擎只按它参与<b>分组</b>（动作 × 行主键），不解析它的语义。
 *
 * <p>它是仓储适配层的公共契约（三个仓储的计数写入共用），不属于合并引擎包——引擎在
 * {@code infrastructure.merge}，只认识泛型件与 {@code MergeStrategy}，不认识这里。
 *
 * <p>五个动作打的行不一样（{@code spec §1.1}）：前三个与关系行同事务、必须精确；
 * 后两个是 UV 口径（明细行 + 加速列）。
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
    PRODUCT_VIEW
}
