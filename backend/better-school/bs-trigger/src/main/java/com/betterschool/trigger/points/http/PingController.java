package com.betterschool.trigger.points.http;

import com.betterschool.api.dto.UserBasicResponseDTO;
import com.betterschool.api.response.Response;
import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.trigger.http.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 探活接口（合并前 points / draw 两域各一份，随单体回归留一份在 points 域）：
 * 返回当前登录用户的基本信息。
 * <p>
 * 它是「Nacos 配置 → JWT 验签 → 用户域查询」这条链路的端到端证人——任何一环断了这里就直接报错，
 * 比 /actuator/health 更能说明问题。合并前经 Dubbo 跨服务查单体用户中心；单体回归后该同步边退役，
 * 改为进程内直调用户域仓储（IUserRepository）。
 */
@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PingController {

    private final IUserRepository userRepository;

    @GetMapping("/ping")
    public Response<UserBasicResponseDTO> ping() {
        UserEntity userEntity = userRepository.findById(SecurityUtils.currentUserId());
        if (userEntity == null) {
            return Response.ok(null);
        }
        UserBasicResponseDTO userBasicResponseDTO = new UserBasicResponseDTO();
        userBasicResponseDTO.setUserId(userEntity.getUserId());
        userBasicResponseDTO.setNickname(userEntity.getNickname());
        userBasicResponseDTO.setAvatar(userEntity.getAvatar());
        userBasicResponseDTO.setCreatedAt(userEntity.getCreatedAt());
        return Response.ok(userBasicResponseDTO);
    }
}
