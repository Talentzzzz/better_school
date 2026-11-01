package com.betterschool.domain.timeout.model.valobj;

/**
 * 超时任务状态（5 态，无 PROCESSING——中间态一律用条件 UPDATE 的行级守卫表达，
 * 不引入需要心跳续租的 PROCESSING；口径沿用原独立超时库的分表 DDL）。
 * <p>
 * 库列 {@code status VARCHAR(16)} 存枚举名（不用 ordinal）：这列是运维/管理面直接读的载体，可读性优先。
 */
public enum TaskStatusVO {

    WAITING,
    RETRYING,
    SUCCESS,
    FAILED,
    CANCELED,
    ;

    /** 终态：扫描 WHERE 永远排除这三态，终态行永久保留不归档 */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELED;
    }
}
