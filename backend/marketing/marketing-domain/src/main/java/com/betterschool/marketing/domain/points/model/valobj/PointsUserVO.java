package com.betterschool.marketing.domain.points.model.valobj;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户基本信息（积分域的跨服务读视图）：对应单体 {@code bs-api} 的 UserBasicDTO，
 * 由 infrastructure 的端口实现完成翻译。
 * <p>
 * 每个消费方域各自持有自己的读视图（照单体 chat / community 的先例）：要什么字段就在本域加，
 * 不去共享一个「大而全」的用户 VO。
 */
@Data
public class PointsUserVO {

    private String userId;
    private String nickname;
    private String avatar;
    /** 注册时间（探活接口透出给前端） */
    private LocalDateTime createdAt;
}
