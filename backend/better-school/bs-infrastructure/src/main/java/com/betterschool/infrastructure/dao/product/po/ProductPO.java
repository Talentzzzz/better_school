package com.betterschool.infrastructure.dao.product.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ProductPO {

    private Long id;
    /** 业务 ID（对外唯一标识，UUID 字符串；uuid-biz-id 票 05 起落库前发号，与物理自增不再同值） */
    private String productId;
    private String userId;
    private String title;
    private String description;
    private String images;
    private BigDecimal price;
    private String tradeLocation;
    private String category;
    private String state;
    /** 审核轮次：累计进入待审次数（直接发布=1，草稿提交/重新上架/在售改内容回审再 +1） */
    private Integer reviewRound;
    /** 最近一次进入待审时间（24h 待审超时提醒扫描用） */
    private LocalDateTime reviewEnteredAt;
    private Integer viewCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime expireAt;

    /** 查询冗余字段：卖家昵称/头像（JOIN bs_user 填充） */
    private String sellerNickname;
    private String sellerAvatar;
}
