package com.betterschool.domain.errand.adapter.port;

import java.math.BigDecimal;

/**
 * 跑腿支付资金收口端口（ADR-0014 组合事务端口模式，照营销侧 PointsDeductPort 形状）：
 * 消费方域（跑腿）声明，实现落 infrastructure——把「悬赏冻结托管」与「订单对外可见」收进
 * 同一个物理事务，消灭「发布成功但钱没冻」的中间态。
 *
 * <p>域边界：本端口是跑腿域使用钱包域资金能力的唯一写入口——跑腿域不直接写钱包表，
 * 钱包域也不写跑腿表；事务编排落在基础设施层端口适配器内（域服务不注入 {@code TransactionTemplate}）。
 */
public interface IErrandFundPort {

    /**
     * 支付组合事务：悬赏冻结托管（流水先行 + 可用余额条件扣减）与订单「待支付 → 已发布」条件翻转
     * （含 bs-errand-paid outbox 事件行 + feed 索引行诞生）在一个物理事务内同生共死。
     * <ul>
     *   <li>可用余额不足 → 抛业务异常，整事务回滚，订单留待支付；</li>
     *   <li>重复执行由流水 {@code uk_order_type} 唯一键幂等吸收（已冻结过则零副作用）；</li>
     *   <li>条件翻转未命中（状态已变化 / deadline 已过）→ 抛业务异常，冻结随之回滚。</li>
     * </ul>
     *
     * @param orderId     跑腿订单业务 ID（流水幂等键 order_id）
     * @param publisherId 发布方用户 ID（冻结扣款对象）
     * @param reward      悬赏金额（冻结口径与后续退款口径同值）
     */
    void payWithFundFreeze(String orderId, String publisherId, BigDecimal reward);
}
