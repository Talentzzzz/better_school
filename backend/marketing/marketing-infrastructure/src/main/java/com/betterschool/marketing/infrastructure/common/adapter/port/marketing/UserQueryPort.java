package com.betterschool.marketing.infrastructure.common.adapter.port.marketing;

import com.betterschool.api.IUserQueryService;
import com.betterschool.api.dto.UserBasicDTO;
import com.betterschool.marketing.domain.common.adapter.port.marketing.IUserQueryPort;
import com.betterschool.marketing.domain.common.model.valobj.marketing.UserBasicVO;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

/**
 * 用户只读查询端口实现（Dubbo Consumer）：单体是 Provider。
 * check = false —— 单体没起时本服务照样能启动，第一次调用才暴露不可用（下单/抽奖主链路不受影响）。
 */
@Component
public class UserQueryPort implements IUserQueryPort {

    @DubboReference(check = false, timeout = 3000)
    private IUserQueryService userQueryService;

    @Override
    public UserBasicVO getBasic(String userId) {
        return toVO(userQueryService.getBasic(userId));
    }

    private static UserBasicVO toVO(UserBasicDTO dto) {
        if (dto == null) {
            return null;
        }
        UserBasicVO vo = new UserBasicVO();
        vo.setUserId(dto.getUserId());
        vo.setNickname(dto.getNickname());
        vo.setAvatar(dto.getAvatar());
        vo.setCreatedAt(dto.getCreatedAt());
        return vo;
    }
}
