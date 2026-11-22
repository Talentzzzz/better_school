package com.betterschool.api.lottery.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用积分购买抽奖次数请求（路径沿用历史的 {@code /exchange/draw-count}，语义是「买次数」）：
 * {@code token} 是前端为「这一次购买意图」生成的 uuid，**同一意图重试必须带同一个**——
 * {@code (userId, token)} 确定性映射成购买单号（积分流水与次数桶的幂等键 refId），
 * 同 token 重放只成功入账一次。
 * <p>
 * 与单抽的 {@link DrawRequestDTO} 同口径：写接口的幂等键由客户端提供，服务端不现编单号
 * （现编 = 网络重试变成一笔新购买 = 重复扣积分）。
 */
@Data
public class RedeemDrawCountRequestDTO {

    @NotBlank(message = "缺少幂等 token")
    @Size(max = 64, message = "幂等 token 过长")
    private String token;

    /** 购买份数（1 次抽奖 = 1 份），不传按 1 份；上限与服务端常量一致 */
    @Min(value = 1, message = "购买份数至少 1")
    @Max(value = 10, message = "一次最多买 10 份")
    private Integer quantity = 1;

    /** 缺省或显式传 null 都按 1 份处理，避免老客户端不带该字段时买到 0 次 */
    public int quantityOrDefault() {
        return quantity == null ? 1 : quantity;
    }
}
