package com.betterschool.domain.errand.model.entity;

import com.betterschool.domain.errand.model.valobj.ErrandStatusVO;
import com.betterschool.domain.errand.model.valobj.ErrandTimeLimitVO;
import com.betterschool.domain.errand.model.valobj.ErrandTypeVO;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ErrandOrderEntity {

    /** 跑腿订单业务 ID（UUID 字符串，uuid-biz-id 票 05） */
    private String orderId;
    private String publisherId;
    private String takerId;
    private ErrandTypeVO type;
    private String description;
    private String pickupLocation;
    private String deliveryLocation;
    private BigDecimal reward;
    /** 时限小时数（草稿允许为空，提交时必填） */
    private Integer timeLimitHours;
    private LocalDateTime deadline;
    private ErrandStatusVO status;

    /** 配图 URL 列表（最多 6 张，创建时随单保存） */
    private List<String> images;

    /** 接单方取消幂等位：取消过为 true（重试判定用，防重复扣分） */
    private boolean takerCanceled;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 浏览 UV 数（market-feed-uv 票 02）：详情读路径由 {@code errand_view_count} 接线填充；
     * 列表查询不填充（恒 0），跑腿此前无浏览量、非推荐列表不展示
     */
    private long viewCount;

    /** 查询冗余字段：下单方/接单方昵称头像（列表 JOIN 填充） */
    private String publisherNickname;
    private String publisherAvatar;
    private String takerNickname;
    private String takerAvatar;

    public static ErrandOrderEntity create(String publisherId, ErrandTypeVO type, String description,
                                           String pickupLocation, String deliveryLocation, BigDecimal reward,
                                           List<String> images, ErrandTimeLimitVO timeLimit) {
        ErrandOrderEntity orderEntity = new ErrandOrderEntity();
        orderEntity.publisherId = publisherId;
        orderEntity.type = type;
        orderEntity.description = description;
        orderEntity.pickupLocation = pickupLocation;
        orderEntity.deliveryLocation = deliveryLocation;
        orderEntity.reward = reward;
        orderEntity.images = images == null ? List.of() : images;
        // 先付后审：创建进入待支付（不冻结），支付确认转待审；deadline 从支付时间起算
        orderEntity.timeLimitHours = timeLimit.getHours();
        orderEntity.status = ErrandStatusVO.PENDING_PAYMENT;
        orderEntity.deadline = null;
        return orderEntity;
    }

    /**
     * 创建草稿：私有未提交态——字段可缺省（提交时才校验必填）、落 DRAFT、deadline 为空；
     * 不付款、不对外、不注册支付超时（支付钟只在提交后起算）
     */
    public static ErrandOrderEntity createDraft(String publisherId, ErrandTypeVO type, String description,
                                               String pickupLocation, String deliveryLocation, BigDecimal reward,
                                               Integer timeLimitHours, List<String> images) {
        ErrandOrderEntity orderEntity = new ErrandOrderEntity();
        orderEntity.publisherId = publisherId;
        orderEntity.type = type;
        orderEntity.description = description;
        orderEntity.pickupLocation = pickupLocation;
        orderEntity.deliveryLocation = deliveryLocation;
        orderEntity.reward = reward;
        orderEntity.timeLimitHours = timeLimitHours;
        orderEntity.images = images == null ? List.of() : images;
        orderEntity.status = ErrandStatusVO.DRAFT;
        orderEntity.deadline = null;
        return orderEntity;
    }

    /**
     * 草稿全量编辑：内容字段整体覆盖（null = 该项留空），
     * publisherId 与状态位不在改动范围内——编辑不产生状态迁移
     */
    public void applyDraftContent(ErrandTypeVO type, String description, String pickupLocation,
                                  String deliveryLocation, BigDecimal reward, Integer timeLimitHours,
                                  List<String> images) {
        this.type = type;
        this.description = description;
        this.pickupLocation = pickupLocation;
        this.deliveryLocation = deliveryLocation;
        this.reward = reward;
        this.timeLimitHours = timeLimitHours;
        this.images = images == null ? List.of() : images;
    }

    /** 支付总额：即悬赏（冻结口径与退款口径均为此值） */
    public BigDecimal getTotalReward() {
        return reward;
    }

    /** 当前接单人数：由 taker_id 判定 */
    public int getCurrentTakers() {
        return takerId != null ? 1 : 0;
    }

    public boolean isOwner(String userId) {
        return publisherId != null && publisherId.equals(userId);
    }

    public boolean isTaker(String userId) {
        return takerId != null && takerId.equals(userId);
    }
}
