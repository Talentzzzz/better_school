package com.betterschool.domain.timeout.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 一次扫描 tick 的账：认领了几个槽、拉了几批、处理了几行、成功几行、进重试几行、进死信几行。
 * （日志文案在触发层的 job 里拼，域对象只管数字。）
 */
@Getter
@AllArgsConstructor
public class ScanSummaryVO {

    /** 本实例本次认领的槽位数（槽位 ≥ 实例数时 = TOTAL / shardTotal 上下取整） */
    private final int slotCount;

    /** 拉到的批次数：批数 > 槽位数即说明「单批拉不完、循环拉批到空」真的发生过 */
    private final int batchCount;

    private final int taskCount;

    private final int successCount;

    private final int retryCount;

    /** 本轮结算进 FAILED 死信的行数：非零就该在调度日志与告警里看见 */
    private final int deadCount;
}
