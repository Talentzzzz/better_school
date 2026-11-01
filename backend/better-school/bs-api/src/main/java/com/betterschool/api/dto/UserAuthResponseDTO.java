package com.betterschool.api.dto;

import lombok.Data;

@Data
public class UserAuthResponseDTO {

    private String token;
    private UserResponseDTO userInfo;
}
