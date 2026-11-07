package com.betterschool.infrastructure.dao.timeout.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 超时任务分表 PO（表结构见 better-school/sql/schema.sql 的 timeout_task_00..15）。
 * status 用 String 承枚举名：库列就是 VARCHAR(16)，别在 PO 层做枚举转换。
 * 没有 callbackGroup——进程内分发后路由列已删（内嵌化票 02）。
 */
@Data
public class TimeoutTask {

    private Long id;
    private String taskType;
    private String bizId;
    /** 仅写路径载体（注册/终态复活的延时秒数）：SQL 侧 TIMESTAMPADD 换算绝对时刻，无对应列 */
    private Long delaySeconds;
    private LocalDateTime actionTime;
    private LocalDateTime nextFireTime;
    private String status;
    private Integer retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
