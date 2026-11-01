package com.betterschool.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserSearchResponseDTO {

    private String userId;
    private String nickname;
    private String avatar;
}
