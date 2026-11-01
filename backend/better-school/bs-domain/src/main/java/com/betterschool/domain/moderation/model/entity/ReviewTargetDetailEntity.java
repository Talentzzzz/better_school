package com.betterschool.domain.moderation.model.entity;

import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 工单详情的内容素材段（spec §5.1 多类型统一「通用列 + 类型专属上下文」）：
 * 通用列 = 内容/配图/作者（昵称/头像）/业务行状态；类型专属 = 跑腿悬赏（reward 列，
 * 商品价格沿用审核队列口径复用此列）、评论的所属帖子/父评论上下文（其余类型为 null）。
 * 按类型分支直查业务表实时取材（工单只存定位与派单态，素材不进工单列）
 */
@Data
public class ReviewTargetDetailEntity {

    private ReviewTargetTypeVO targetType;
    private String targetId;
    private String authorId;
    private String authorNickname;
    private String authorAvatar;
    /** 内容全文（帖=标题+正文，商=标题+描述，评=评论文本，跑腿=描述） */
    private String content;
    /** 配图 URL 列表（帖/商/评/跑腿有值则为作者提交的原图） */
    private List<String> images;
    /** 跑腿悬赏 / 商品价格（沿用审核队列 reward 列口径；帖/评为空） */
    private BigDecimal reward;
    /** 业务行当前状态（只读展示；本详情不改变任何业务行状态与可见性） */
    private String status;
    // ---- 评论类型专属上下文（其余类型为 null） ----
    /** 所属帖子业务 ID（评论类型专属） */
    private String contextPostId;
    /** 所属帖子标题（评论类型专属） */
    private String contextPostTitle;
    /** 父评论业务 ID（根评论为 null） */
    private String contextParentId;
    /** 父评论文本（根评论为 null） */
    private String contextParentContent;
}
