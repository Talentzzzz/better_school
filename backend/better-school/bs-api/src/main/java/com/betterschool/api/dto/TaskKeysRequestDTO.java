package com.betterschool.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量重发死信的入参（内嵌化票 06）：键列表（单条也放进列表，形状与批量一致）。
 */
@Data
public class TaskKeysRequestDTO {

    private List<TaskKeyDTO> keys;
}
