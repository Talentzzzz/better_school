package com.betterschool.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateDirectRoomRequestDTO {

    /** 目标用户 ID（「联系 TA」入口），不能是自己 */
    @NotNull(message = "目标用户不能为空")
    private String targetUserId;

    /**
     * 会话来源（可选）：市集两入口分别传 ERRAND / PRODUCT，
     * 首页与用户主页的私信入口不传。仅在首次建会话时生效（首来源钉死）
     */
    private String source;
}
