package com.betterschool.marketing.api.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户基本信息（对外 HTTP 形态）：触发层把域内 UserBasicVO 映射到这里返回，
 * 域类型不外溢到 HTTP 契约（CLAUDE.md §5：api 层放 DTO，Controller 不返回域对象）。
 */
@Data
public class UserBasicResponseDTO {

    private String userId;
    private String nickname;
    private String avatar;
    private LocalDateTime createdAt;
}
