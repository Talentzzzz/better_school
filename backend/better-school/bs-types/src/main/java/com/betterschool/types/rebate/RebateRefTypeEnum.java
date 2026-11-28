package com.betterschool.types.rebate;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 行为返利订单引用的行为对象类型（{@code action_rebate_order.ref_type} 的取值）。
 * <p>
 * 与 {@link RebateActionTypeEnum} 分开：行为类型说明「发生了什么行为」，对象类型说明「ref_id 是谁的 ID」。
 * 现在两者一一对应（发帖行为引用帖子 ID），后续同一行为引用不同对象时只扩展这里。
 */
@Getter
@AllArgsConstructor
public enum RebateRefTypeEnum {

    /** 帖子：ref_id = postId */
    POST("post");

    private final String code;
}
