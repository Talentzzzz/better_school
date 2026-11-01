package com.betterschool.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 死信管理面上指认一条任务的键（= 自然键 {@code (taskType, bizId)}，内嵌化票 06）。
 * 放 bs-api 是沿用「Controller 实现 api 接口、DTO 进 api.dto」的工程约定
 * （与 AdminReviewController 同款）；虽然它只被运维面消费。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskKeyDTO {

    private String taskType;
    private String bizId;
}
