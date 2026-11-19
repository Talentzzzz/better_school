package com.betterschool.marketing.domain.raffle.model.valobj;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户基本信息（抽奖域的跨服务读视图）：对应单体 {@code bs-api} 的 UserBasicDTO，
 * 由 infrastructure 的端口实现完成翻译。
 * <p>
 * 与积分域各持一份（照单体 chat / community 的先例）：抽奖侧之后要补昵称头像展示时，
 * 在本 VO 上按需加字段，不跨域共享同一份用户读视图。
 */
@Data
public class RaffleUserVO {

    private String userId;
    private String nickname;
    private String avatar;
    /** 注册时间（探活接口透出给前端） */
    private LocalDateTime createdAt;
}
