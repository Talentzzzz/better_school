package com.betterschool.infrastructure.dao.moderation.po;

import lombok.Data;

import java.math.BigDecimal;

/** 工单详情内容素材统一结果 PO（post/post_comment/product/errand_order 单表直查，字段求同 + 类型专属上下文；
 * authorNickname/authorAvatar 与评论的 contextPostTitle/contextParentContent 由仓储二次回查拼装） */
@Data
public class ReviewTaskTarget {

    private String targetType;
    private String targetId;
    private String authorId;
    private String authorNickname;
    private String authorAvatar;
    private String content;
    /** 配图 URL 列表（post/product/post_comment/errand_order images 原始 JSON 串） */
    private String images;
    /** 跑腿悬赏 / 商品价格（帖/评为空） */
    private BigDecimal reward;
    /** 业务行当前状态 */
    private String status;
    // ---- 评论类型专属上下文（其余类型为 null） ----
    private String contextPostId;
    private String contextPostTitle;
    private String contextParentId;
    private String contextParentContent;
}
