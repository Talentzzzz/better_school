package com.betterschool.infrastructure.dao.errand.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ErrandOrder {

    private Long id;
    /** 跑腿订单业务 ID（UUID 字符串，uuid-biz-id 票 05；物理自增 id 只作内部键，不再同值） */
    private String errandOrderId;
    private String publisherId;
    private String takerId;
    private String type;
    /** 订单标题（草稿允许为空，提交时必填） */
    private String title;
    private String description;
    private String pickupLocation;
    private String deliveryLocation;
    private BigDecimal reward;
    /** 配图 URL 列表（JSON 数组，与 post.images 存储约定一致） */
    private String images;
    /** 截止时间（用户指定的绝对时刻；到期自动下架与接单方完工时限共用） */
    private LocalDateTime deadline;
    private String state;
    /** 事件序号计数器（事件表取号用；同订单内递增即事件先后顺序，业务含义只认 event_seq） */
    private Integer eventSeq;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 查询冗余字段：下单方/接单方昵称头像（仓储查 bs_user 批量回查填充，SQL 层零 JOIN） */
    private String publisherNickname;
    private String publisherAvatar;
    private String takerNickname;
    private String takerAvatar;

    /** 查询冗余字段：浏览 UV 数（仓储从 errand_view_count 批量回查填充；其余查询为 null，详情另走计数窄表） */
    private Integer viewCount;
}
