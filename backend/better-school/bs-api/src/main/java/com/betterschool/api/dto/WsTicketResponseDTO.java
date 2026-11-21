package com.betterschool.api.dto;

import lombok.Data;

/**
 * WS 连接票据（60s 一次性）：先取票再连 `GET /ws?ticket=`
 */
@Data
public class WsTicketResponseDTO {

    private String ticket;
}
