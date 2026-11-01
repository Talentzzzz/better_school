package com.betterschool.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量重发死信的响应（内嵌化票 06）：成功/失败双清单。
 * 失败项 = 该键当前不是 FAILED（别人已经处理过、或键写错了），逐项回报比整包失败有用。
 */
@Data
public class DeadLetterResendResultDTO {

    private List<TaskKeyDTO> successList = new ArrayList<>();
    private List<TaskKeyDTO> failList = new ArrayList<>();
}
