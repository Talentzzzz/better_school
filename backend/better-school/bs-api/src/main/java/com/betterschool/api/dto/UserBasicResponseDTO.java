package com.betterschool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户基本信息（对外 HTTP 形态）：触发层把用户域实体读视图映射到这里返回，
 * 域类型不外溢到 HTTP 契约（CLAUDE.md §5：api 层放 DTO，Controller 不返回域对象）。
 * 合并前 marketing 侧 PingController 经 Dubbo IUserQueryPort 取数后映射本 DTO。
 */
@Data
public class UserBasicResponseDTO {

    private String userId;
    private String nickname;
    private String avatar;
    private LocalDateTime createdAt;
}
