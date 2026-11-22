package com.betterschool.domain.points.service;

import com.betterschool.domain.points.model.valobj.PointsDeductResultVO;
import com.betterschool.domain.points.model.valobj.PointsTransactionVO;
import com.betterschool.types.points.PointsReasonType;

import java.util.List;

/**
 * 积分账户服务（桶模型，sql.md 积分段）：入账 / 消费 / 余额 / 流水 / 过期。
 * <p>
 * 没有余额列——余额是未过期桶 remain 之和；消费按 FIFO 扣各桶 remain，凑不够则整事务回滚。
 * 每个方法自己域内事务原子，跨域组合由调用方（单体 MQ 消费 / 抽奖服务的 Dubbo 调用）顺序编排。
 */
public interface IPointsAccountService {

    /**
     * 抽奖中奖入账（消费 {@code bs-lottery-drawn}）：幂等键 = {@code (userId, LOTTERY_WIN, recordId)}，
     * MQ 重投不会重复加分、也不会重复发事件。
     */
    void addForLotteryWin(String userId, String recordId, int pointsAmount);

    /**
     * 行为返利入账（签到 / 支付等，规则见 {@code action_rebate_config} 的 POINTS 行）：
     * 规则未配或已关闭 → 返回 false 什么都不做；命中则落一行 REBATE 桶并发变动事件，
     * 幂等键 = {@code (userId, REBATE, refId)}（refId = 调用方的发放幂等键）。
     */
    boolean creditForRebate(String actionType, String userId, String refId);

    /**
     * 兑换扣分（Dubbo 同步边，幂等键 = 兑换单 id）：FIFO 跨桶扣减，凑不够返回「积分不足」而不扣任何一行。
     * 业务失败以结果对象返回，不抛异常——异常跨 Dubbo 会把「积分不足」这个语义抹平。
     */
    PointsDeductResultVO deduct(String userId, int amount, PointsReasonType reasonType, String refId);

    /** 我的积分余额 */
    int getBalance(String userId);

    List<PointsTransactionVO> listTransactions(String userId, int page, int pageSize);

    long countTransactions(String userId);

    /**
     * 桶合并（points-compaction 票，xxl-job 调度）：把同用户的「有效小额桶」（remain &le; 阈值且
     * 数量 &ge; 2）并成一桶，过期时间取合并集合最大值、旧桶归零留审计，SUM(remain) 守恒、用户无感。
     *
     * @return 本轮实际合并（清零）的旧桶总数
     */
    int compactSmallBuckets();

    /** 积分有效期天数（本域 points_validity_config，后台读 + 入账算 expire_at 同口径） */
    int getValidDays();

    /** 改积分有效期天数（后台管理面，ADMIN）；天数必须为正，不合法抛 CONFIG_PARAM_ERROR */
    void updateValidDays(int days);
}
