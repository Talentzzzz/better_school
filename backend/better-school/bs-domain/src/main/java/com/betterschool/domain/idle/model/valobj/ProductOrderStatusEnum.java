package com.betterschool.domain.idle.model.valobj;

import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.exception.BusinessException;

/**
 * 商品托管订单状态机：
 * PENDING_PAYMENT →(支付冻结)→ PAID →(卖家标记交付)→ DELIVERED →(买家确认收货)→ COMPLETED(终态)
 * 取消：PENDING_PAYMENT（未冻结无退款）/ PAID（需解冻退款）→ CANCELLED(终态)；15 分钟未支付自动取消
 */
public enum ProductOrderStatusEnum {

    PENDING_PAYMENT, PAID, DELIVERED, COMPLETED, CANCELLED;

    public static ProductOrderStatusEnum from(String name) {
        try {
            return ProductOrderStatusEnum.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "订单状态不合法");
        }
    }
}
