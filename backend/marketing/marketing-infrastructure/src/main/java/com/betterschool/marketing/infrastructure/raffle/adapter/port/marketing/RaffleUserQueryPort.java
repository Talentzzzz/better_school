package com.betterschool.marketing.infrastructure.raffle.adapter.port.marketing;

import com.betterschool.api.IUserQueryService;
import com.betterschool.api.dto.UserBasicDTO;
import com.betterschool.marketing.domain.raffle.adapter.port.marketing.IUserQueryPort;
import com.betterschool.marketing.domain.raffle.model.valobj.RaffleUserVO;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

/**
 * 抽奖域的用户只读查询端口实现（Dubbo Consumer）：单体是 Provider。
 * <p>
 * check = false —— 单体没起时本服务照样能启动，第一次调用才暴露不可用（抽奖主链路不受影响）。
 * 与积分域的实现各管各的：消费方域各自声明端口、各自实现，不为一个「公共用户口」造共享域。
 */
@Component
public class RaffleUserQueryPort implements IUserQueryPort {

    @DubboReference(check = false, timeout = 3000)
    private IUserQueryService userQueryService;

    @Override
    public RaffleUserVO getBasic(String userId) {
        return toVO(userQueryService.getBasic(userId));
    }

    private static RaffleUserVO toVO(UserBasicDTO dto) {
        if (dto == null) {
            return null;
        }
        RaffleUserVO vo = new RaffleUserVO();
        vo.setUserId(dto.getUserId());
        vo.setNickname(dto.getNickname());
        vo.setAvatar(dto.getAvatar());
        vo.setCreatedAt(dto.getCreatedAt());
        return vo;
    }
}
