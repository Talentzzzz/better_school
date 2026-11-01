package com.betterschool.marketing.api.points;

import com.betterschool.marketing.api.points.dto.PointsDeductRequestDTO;
import com.betterschool.marketing.api.points.dto.PointsDeductResponseDTO;

/**
 * 兑换边（同步 Dubbo）：抽奖服务扣积分时调，Provider 在积分服务
 * {@code marketing-trigger/.../trigger/points/rpc/PointsExchangeDubboService}。
 * <p>
 * 为什么不是 HTTP：兑换是「先落兑换单拿幂等键 → 扣分 → 落本地事务」的两段式写链路，
 * 抽奖服务需要同步拿到扣分结果才能决定失败返回；Dubbo 让积分服务对外 HTTP 面保持零写接口。
 * <p>
 * 幂等：同一个兑换单 id 重复调用不得重复扣分（积分侧按消费流水判定，重放返回当前余额）。
 */
public interface IPointsExchangeService {

    PointsDeductResponseDTO deduct(PointsDeductRequestDTO request);
}
