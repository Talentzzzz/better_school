package cn.inventory.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 响应码
 *
 * <p>契约约定：<b>HTTP 状态码恒 200</b>，业务结果全部通过 {@code code} 表达。
 * 这样调用方（含压测工具）不需要写状态码分支，失败率统计也不会被 4xx/5xx 的传输层语义污染。
 */
@Getter
@AllArgsConstructor
public enum ResponseCode {

    SUCCESS("0000", "调用成功"),
    UN_ERROR("0001", "调用失败，请重试"),

    INSUFFICIENT("1001", "库存不足"),
    DUPLICATE("1002", "重复请求，已按幂等处理"),

    /** 超时但结果未知：一定不能表达成「扣减失败」 */
    MERGE_PROCESSING("1003", "处理中，请凭原单号查询结果或重试"),
    QUEUE_REJECTED("1004", "系统繁忙，请稍后重试"),
    PARAM_ERROR("1005", "参数错误"),

    ;

    private final String code;
    private final String info;
}
