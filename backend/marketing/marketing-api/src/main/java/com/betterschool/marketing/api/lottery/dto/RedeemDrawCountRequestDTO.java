package com.betterschool.marketing.api.lottery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 兑抽奖次数请求：{@code token} 是前端为「这一次兑换意图」生成的 uuid，
 * **同一意图重试必须带同一个**——票 13 起 {@code (userId, token)} 确定性映射成
 * {@code redeemOrderId}（两域流水的幂等键 refId），同 token 重放只成功入账一次。
 * <p>
 * 与单抽的 {@link DrawRequestDTO} 同口径：写接口的幂等键由客户端提供，服务端不现编单号
 * （现编 = 网络重试变成一笔新兑换 = 重复扣积分）。
 */
@Data
public class RedeemDrawCountRequestDTO {

    @NotBlank(message = "缺少幂等 token")
    @Size(max = 64, message = "幂等 token 过长")
    private String token;
}
