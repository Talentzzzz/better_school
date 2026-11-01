package com.betterschool.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ErrandOrderResponseDTO {

    /** 跑腿订单业务 ID（UUID 字符串，uuid-biz-id 票 05） */
    private String orderId;
    private String publisherId;
    private String publisherNickname;
    private String publisherAvatar;
    private String takerId;
    private String takerNickname;
    private String takerAvatar;
    private String type;
    private String description;
    private String pickupLocation;
    private String deliveryLocation;
    /** 悬赏（支付时冻结） */
    private BigDecimal reward;
    /** 当前接单人数（0/1） */
    private int currentTakers;
    /** 浏览 UV 数（按用户去重，一人一单只计一次，匿名不计入；仅详情填充，列表恒 0） */
    private long viewCount;
    private LocalDateTime deadline;
    private String status;
    private LocalDateTime createdAt;

    /** 配图 URL 列表（最多 6 张，无配图为空列表） */
    private List<String> images;

    /** 拒绝理由：仅作者视角且订单 REJECTED 时返回（读 content_review.reason），他人/其他状态不带 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String rejectReason;

    /**
     * 当前用户是否浏览过（market-feed-uv 票 05 已读灰显标记）：仅大厅推荐列表接口填充，详情与其他列表 null
     * （未登录 false——大厅匿名可浏览，灰显只对登录用户有意义）
     */
    private Boolean viewed;
}
