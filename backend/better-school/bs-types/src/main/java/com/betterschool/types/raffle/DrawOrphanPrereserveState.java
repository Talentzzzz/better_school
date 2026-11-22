package com.betterschool.types.raffle;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 孤儿预扣对账单状态机（与购买单三态同构）：只有三态，刻意没有 FAILED（两条收敛路径
 * 正向重试必收敛，永不回滚）也没有 PROCESSING（并发抢占靠 {@code UPDATE ... WHERE state=0}
 * 条件更新解决）。
 * <p>
 * 「ORPHAN」与种单扫描的关系：对账单由扫描「预扣在、抽奖记录不在」的明细行种出
 * （INSERT IGNORE），ORPHAN 即待收敛；两条收敛路径（决策可重放补落库 / 不可重放回补+退还）
 * 成功即置 DONE；重试耗尽进 DEAD 等人工介入（人工 SQL 置回 ORPHAN 重新可扫）。
 */
@Getter
@AllArgsConstructor
public enum DrawOrphanPrereserveState {

    /** 待收敛（种单时的初始态） */
    ORPHAN(0),

    /** 已收敛（记录补落库，或库存回补 + 次数退还完成） */
    DONE(1),

    /** 死信：补偿重试耗尽，人工介入后置回 ORPHAN 重新可扫 */
    DEAD(2);

    private final int code;

    public static DrawOrphanPrereserveState of(int code) {
        for (DrawOrphanPrereserveState state : values()) {
            if (state.code == code) {
                return state;
            }
        }
        throw new IllegalArgumentException("未知孤儿预扣对账单状态 code=" + code);
    }
}
