package com.betterschool.domain.timeout.model.valobj;

import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 死信列表的一页：{@code total} 与本页行分两次广播查询，凑成一个分页结果（内嵌化票 06）。
 * <p>
 * 跨 16 张分表的广播查询没法在一次往返里同时拿 total 与本页（SS 会重写成两条 SQL），
 * 所以这里承载的两件事天然来自两个查询——别把 total 当成「本页行数」。
 */
@Getter
@AllArgsConstructor
public class DeadLetterPageVO {

    private final long total;

    private final List<TimeoutTaskEntity> tasks;
}
