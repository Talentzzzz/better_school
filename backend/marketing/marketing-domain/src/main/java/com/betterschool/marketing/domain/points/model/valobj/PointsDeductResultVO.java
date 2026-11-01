package com.betterschool.marketing.domain.points.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 扣积分结果（域内结果对象）：Dubbo 边不靠异常表达业务失败——
 * 抽奖服务要把「积分不足」原样变成自己的业务码返回前端，异常跨进程会把语义抹平。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PointsDeductResultVO {

    private boolean success;
    private String code;
    private String info;
    /** 扣减后余额（成功与幂等重放都有值；积分不足时为当前余额） */
    private Integer balance;
}
