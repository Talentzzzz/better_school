package com.betterschool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserResponseDTO {

    private String userId;
    private String phone;
    private String nickname;
    private String avatar;
    private String role;
    private String status;
    private Integer creditScore;
    private LocalDateTime lastLoginAt;
}
