package com.betterschool.marketing.infrastructure.points.adapter.port.marketing;

import com.betterschool.api.IUserQueryService;
import com.betterschool.api.dto.UserBasicDTO;
import com.betterschool.marketing.domain.points.adapter.port.marketing.IUserQueryPort;
import com.betterschool.marketing.domain.points.model.valobj.PointsUserVO;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

/**
 * 积分域的用户只读查询端口实现（Dubbo Consumer）：单体是 Provider。
 * <p>
 * check = false —— 单体没起时本服务照样能启动，第一次调用才暴露不可用（抽奖主链路不受影响）。
 * 翻译只做 DTO → 本域 VO，别的域要不要用户信息与这里无关（各域各自声明、各自实现）。
 */
@Component
public class PointsUserQueryPort implements IUserQueryPort {

    @DubboReference(check = false, timeout = 3000)
    private IUserQueryService userQueryService;

    @Override
    public PointsUserVO getBasic(String userId) {
        return toVO(userQueryService.getBasic(userId));
    }

    private static PointsUserVO toVO(UserBasicDTO dto) {
        if (dto == null) {
            return null;
        }
        PointsUserVO vo = new PointsUserVO();
        vo.setUserId(dto.getUserId());
        vo.setNickname(dto.getNickname());
        vo.setAvatar(dto.getAvatar());
        vo.setCreatedAt(dto.getCreatedAt());
        return vo;
    }
}
