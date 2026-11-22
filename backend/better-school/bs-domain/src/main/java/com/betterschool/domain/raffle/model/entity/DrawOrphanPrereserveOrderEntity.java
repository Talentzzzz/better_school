package com.betterschool.domain.raffle.model.entity;

import com.betterschool.types.raffle.DrawOrphanPrereserveState;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 孤儿预扣对账单：一次「预扣成功而抽奖记录未落库」的收敛台账（ADR-0014 状态表形状，
 * 照购买单裁剪）。种单扫描发现孤儿预扣（明细 {@code flow_type=1 AND biz_type='LOTTERY_DRAW'}
 * 且 {@code biz_id} 在 {@code draw_record} 无对应行、超过补偿阈值）后 INSERT IGNORE 落单，
 * 承载重试状态机与死信；对账单 DONE/DEAD 后，同一孤儿不会再被重复种单。
 * <p>
 * 不变量：对账单在 ⇒ 预扣一定已提交（种单自明细行事实）；收敛只做「补落库」或
 * 「回补 + 退还」，从不碰扣次（扣次明细在即次数已扣）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DrawOrphanPrereserveOrderEntity {

    /** 数据库自增主键（抢占式认领的行锚点） */
    private Long id;
    /** 孤儿抽奖记录业务 ID（= 预扣明细 biz_id，hash(userId, token) 幂等键） */
    private String recordId;
    /** 预扣奖品位 id 快照（种单时自明细行） */
    private String awardId;
    /** 用户 ID 快照（种单时自 draw_count_detail.idx_ref 反查） */
    private String userId;
    /** 预扣份数快照（种单时自明细行，抽奖恒 1） */
    private Long quantity;
    private DrawOrphanPrereserveState state;
    /** 补偿重试次数（抢占时递增） */
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
