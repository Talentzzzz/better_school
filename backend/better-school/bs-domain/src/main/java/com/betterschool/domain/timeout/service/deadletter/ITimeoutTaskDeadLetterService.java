package com.betterschool.domain.timeout.service.deadletter;

import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;

import java.util.List;

/**
 * 死信管理领域服务（内嵌化票 06）：列表翻页 + 逐条重发 + 逐条取消。
 * <p>
 * 死信 = 回调连续失败到上限（默认 16 次）进 {@code FAILED} 终态的行；**扫描永远排除终态**，
 * 所以死信只能从这里人工处理。写动作的裁决权在库上（条件 UPDATE 只打 FAILED 行），
 * 本服务只做转发与告警日志，不持任何「读-判-写」时序。
 */
public interface ITimeoutTaskDeadLetterService {

    /** 死信总数（管理面翻页的 total，与本页行分两次广播查询） */
    long countDeadLetters();

    /**
     * 死信列表的一页：{@code updated_at} 倒序（最近进死信的在前）。
     *
     * @param offset 偏移行数（调用方按 {@code (page-1) * pageSize} 算好传入）
     * @param limit  本页行数（HTTP 边界已夹紧）
     */
    List<TimeoutTaskEntity> listDeadLetters(int offset, int limit);

    /**
     * 重发一条死信：FAILED → RETRYING、{@code retry_count} 清零、{@code next_fire_time = now}——
     * 走同一个扫描 job 入口重投（不新开重试通路），下一个扫描周期即被捞起。
     *
     * @return true = 确有 FAILED 行被重开；false = 该键当前不是死信（无副作用）
     */
    boolean resendDeadLetter(String taskType, String bizId);

    /**
     * 取消一条死信：FAILED → CANCELED 终态。
     *
     * @return true = 确有行被取消；false = 该键当前不是死信（无副作用）
     */
    boolean cancelDeadLetter(String taskType, String bizId);
}
