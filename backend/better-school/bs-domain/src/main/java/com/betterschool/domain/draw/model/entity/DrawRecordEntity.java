package com.betterschool.domain.draw.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抽奖记录（draw_record）：中奖事实与奖品快照，落库归票 13（本票只备五层，不接主链路）。
 * award_* 各列是抽中时的快照，此后不可变；发放状态推进（待兑换 → 已兑换 → 已发货 → 已完成）
 * 每进一步在 draw_record_detail 落一条动作（THANKS 行落库即已完成，不走兑换/发货）。
 */
@Data
public class DrawRecordEntity {

    private Long id;
    /** 业务 ID（对外唯一标识；points_bucket.ref_id 的 LOTTERY_WIN 指向它） */
    private String recordId;
    private String userId;
    private String activityId;
    private String strategyId;
    /** 命中的奖品 ID（策略内唯一） */
    private String awardId;
    /** 奖品名称快照 */
    private String awardName;
    /** 奖品描述快照 */
    private String awardDesc;
    /** 奖品类型快照 VIRTUAL / PHYSICAL / THANKS */
    private String awardType;
    /** 奖项层级快照 */
    private Integer level;
    /** 发放来源库存/配额业务 ID（快照；无库存来源为 null） */
    private String invId;
    /** 待兑换 / 已兑换 / 已发货 / 已完成 */
    private String state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
