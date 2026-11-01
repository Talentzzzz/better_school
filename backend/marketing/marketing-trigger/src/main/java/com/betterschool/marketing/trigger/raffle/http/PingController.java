package com.betterschool.marketing.trigger.raffle.http;

import com.betterschool.marketing.api.common.dto.UserBasicResponseDTO;
import com.betterschool.marketing.api.common.response.Response;
import com.betterschool.marketing.domain.common.adapter.port.marketing.IUserQueryPort;
import com.betterschool.marketing.domain.common.model.valobj.marketing.UserBasicVO;
import com.betterschool.marketing.trigger.common.http.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 探活接口：返回经 Dubbo 从单体取回的当前用户基本信息。
 * <p>
 * 它是本服务「Nacos 配置 → JWT 验签 → Dubbo 同步边」这条链路的端到端证人——
 * 任何一环断了这里就直接报错，比 /actuator/health 更能说明问题。
 */
@RestController
@RequestMapping("/api/lottery")
@RequiredArgsConstructor
public class PingController {

    private final IUserQueryPort userQueryPort;

    @GetMapping("/ping")
    public Response<UserBasicResponseDTO> ping() {
        UserBasicVO userBasicVO = userQueryPort.getBasic(SecurityUtils.currentUserId());
        if (userBasicVO == null) {
            return Response.ok(null);
        }
        UserBasicResponseDTO dto = new UserBasicResponseDTO();
        dto.setUserId(userBasicVO.getUserId());
        dto.setNickname(userBasicVO.getNickname());
        dto.setAvatar(userBasicVO.getAvatar());
        dto.setCreatedAt(userBasicVO.getCreatedAt());
        return Response.ok(dto);
    }
}
