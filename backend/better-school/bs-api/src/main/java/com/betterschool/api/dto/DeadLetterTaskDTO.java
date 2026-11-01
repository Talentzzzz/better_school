package com.betterschool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 死信列表里的一行（只读，内嵌化票 06）：给人看的那几列。
 * <p>
 * 不带 {@code id}——管理面按自然键动作（重发/取消的入参就是 taskType + bizId），
 * 不对外暴露物理主键。{@code actionTime} 与 {@code nextFireTime} 都给：
 * 前者是理论到期时刻（业务对账口径），后者是最后一次重试把它推到的时刻，FAILED 行不再变化。
 */
@Data
public class DeadLetterTaskDTO {

    private String taskType;
    private String bizId;
    private LocalDateTime actionTime;
    private LocalDateTime nextFireTime;
    private Integer retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
