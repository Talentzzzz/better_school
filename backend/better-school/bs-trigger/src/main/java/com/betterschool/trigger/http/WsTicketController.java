package com.betterschool.trigger.http;

import com.betterschool.api.dto.WsTicketResponseDTO;
import com.betterschool.api.response.Response;
import com.betterschool.infrastructure.ws.IWsService;
import com.betterschool.trigger.http.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WS 连接 HTTP 入口：一次性票据签发（60s 有效，握手原子取删）。
 * WS 连接层是全站共享技术设施，不归属 chat——票从 chat 域契约中移出，独立成端点
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WsTicketController {

    private final IWsService wsService;

    @PostMapping("/ws-ticket")
    public Response<WsTicketResponseDTO> createWsTicket() {
        WsTicketResponseDTO dto = new WsTicketResponseDTO();
        dto.setTicket(wsService.createTicket(SecurityUtils.currentUserId()));
        return Response.ok(dto);
    }
}
