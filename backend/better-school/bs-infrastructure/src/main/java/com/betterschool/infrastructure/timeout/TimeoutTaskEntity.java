package com.betterschool.infrastructure.timeout;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 超时任务（一行 = 一个「到某时刻触发一次业务动作」的任务）。
 * <p>
 * 与 PO {@code dao.timeout.po.TimeoutTask} 的分工：PO 是 mapper 的列载体（{@code status} 用 String），
 * 本类多三样东西——枚举化的状态、写路径入参 {@code delaySeconds}、扫描时盖上的 {@code shardSlot}。
 * 转换收在 {@link TimeoutTaskStore}，别在别处再写一套。
 * <p>
 * 标识是自然键 {@code (taskType, bizId)}（业务方永不持久化物理 id）；
 * {@code id} 只是物理主键，无业务含义。复合业务引用（如 {@code {orderId}:{lockSeq}}）编码进 {@code bizId}。
 * <p>
 * 列里没有回调路由列：进程内分发后不再需要业务方应用名（原独立中心的路由列已删）。
 */
@Data
public class TimeoutTaskEntity {

    private Long id;
    private String taskType;
    private String bizId;
    /** 注册/终态复活的延时秒数（仅写路径入参）：SQL 侧 TIMESTAMPADD 换算绝对时刻，读路径无值 */
    private Long delaySeconds;
    /** 理论到期时刻：单轮内不可变（终态复活=重开一轮时刷新），回调时携带，供业务监控精度 */
    private LocalDateTime actionTime;
    /** 下次可触发时刻：注册时 = actionTime，失败重试后 = now + 退避 */
    private LocalDateTime nextFireTime;
    private TimeoutTaskStatus status;
    private Integer retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 这一行属于哪个全局槽位（0..15），<b>不落库</b>：按键读写时由仓储按 {@code bizId} 算出，
     * 扫描捞批时由认领的槽位给——结算批量语句塞 hint 用的是批的槽位，不再逐行算。
     */
    private Integer shardSlot;

    /**
     * 注册态的新任务行：WAITING、{@code nextFireTime = actionTime}、{@code retryCount = 0}。
     * <p>
     * 时刻一律数据库时钟：Java 只传时长（{@code delaySeconds}），action_time/next_fire_time 由
     * SQL 侧 TIMESTAMPADD(SECOND, #{delaySeconds}, NOW()) 落库（NOW() 与库列同为秒级精度）。
     */
    public static TimeoutTaskEntity newWaiting(String taskType, String bizId, long delaySeconds) {
        TimeoutTaskEntity task = new TimeoutTaskEntity();
        task.setTaskType(taskType);
        task.setBizId(bizId);
        task.setDelaySeconds(delaySeconds);
        task.setStatus(TimeoutTaskStatus.WAITING);
        task.setRetryCount(0);
        return task;
    }
}
