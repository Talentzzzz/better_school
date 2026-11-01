package com.betterschool.api.dto;

import lombok.Data;

@Data
public class CreditInfoResponseDTO {

    /** 当前信用分（0-100） */
    private int creditScore;

    /** 账号状态（NORMAL/BANNED） */
    private String status;
}
