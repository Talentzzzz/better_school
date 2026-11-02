package com.betterschool.marketing.domain.raffle.model.valobj;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抽奖活动信息（读侧出参）：draw_activity 当前活动行 + 次数有效期配置。
 * {@link #statusAt} 是给前端 banner 现算的三态，与旧模型的口径一致。
 */
@Data
public class ActivityInfoVO {

    private String activityId;
    private String name;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    /** create / open / close */
    private String state;
    /** 抽奖次数有效期天数（draw_count_config，展示用） */
    private Integer drawCountValidDays;

    /** NOT_STARTED / ONGOING / ENDED（state=close 与过了 end_at 都归 ENDED） */
    public String statusAt(LocalDateTime now) {
        if ("close".equals(state) || now.isAfter(endAt)) {
            return "ENDED";
        }
        if (now.isBefore(startAt)) {
            return "NOT_STARTED";
        }
        return "ONGOING";
    }
}
