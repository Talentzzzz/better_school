package com.betterschool.marketing.api.points;

import com.betterschool.marketing.api.common.response.PageResult;
import com.betterschool.marketing.api.common.response.Response;
import com.betterschool.marketing.api.points.dto.PointsBalanceResponseDTO;
import com.betterschool.marketing.api.points.dto.PointsTransactionResponseDTO;

/**
 * 积分服务对外 HTTP 契约（本服务**只有两个只读接口、零写接口**）。
 * <p>
 * 兑抽奖次数的写入口挂在抽奖服务上（前端打 `POST /api/lottery/exchange/draw-count`）。
 * 票 13 起该链路**不再经 Dubbo**：抽奖域经 `IPointsDeductPort` 进程内调用积分域；
 * 两域各自域内原子、由 case 层顺序编排（无跨域事务），幂等靠两域流水唯一键 + 重试收敛。
 * {@link IPointsExchangeService} 的 Dubbo Provider 仍保留作对外契约，
 * 但本工程内已无调用方。
 */
public interface IPointsService {

    /** 我的积分余额：未过期桶的 SUM(remain) */
    Response<PointsBalanceResponseDTO> getBalance();

    /** 我的积分流水：入账 ∪ 消费按时间归并，created_at DESC */
    Response<PageResult<PointsTransactionResponseDTO>> listTransactions(int page, int pageSize);
}
