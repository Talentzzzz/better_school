package com.betterschool.marketing.domain.raffle.service.drawcount;

import com.betterschool.marketing.domain.raffle.model.entity.DrawCountPurchaseOrderEntity;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawCountPurchaseResultVO;

import java.util.List;

/**
 * 购买单的抽奖域面（ADR-0014 表形态 + ADR-0016 票 C 三段式）：购买单是这条链路的
 * 幂等入口、状态机载体与对账源。本服务只承担**抽奖域内**的单据操作（落单 / 推进 / 认领），
 * 跨域的三段顺序编排（落单 CREATED → 积分域扣分 → 入账置 DONE）在 case 层
 * {@code DrawCountPurchaseCase}——事务边界 = 仓储，每段各自仓储本地事务。
 * <p>
 * 与抽奖主链路的关系：**抽奖只扣次数**（{@link IDrawCountAccountService#deductForDraw}），
 * 从不碰积分；积分不足 / 扣分失败等跨域结果由 case 层裁决后回到本服务的状态机。
 */
public interface IRaffleDrawCountPurchaseService {

    /** 补偿重试上限：第 5 次尝试（retry_count 达 4 时认领）仍失败即置 DEAD，人工介入 */
    int COMPENSATE_MAX_RETRY = 5;

    /**
     * 第一段：校验参数 + 幂等落单（{@code state = CREATED}，raffle 仓储本地事务）。
     * {@code purchaseOrderId = hash(userId, token)}——同 token 重放先查单：
     * 已存在直接返回原单（Stripe 幂等入口，状态由 case 层裁决推进）；
     * 并发同 token 撞 {@code uk_purchase_order_id} 转重查，同样返回胜出的那一行。
     *
     * @param token    前端为「这一次购买意图」生成的 uuid；重试必须带同一个（幂等键来源）
     * @param quantity 购买份数（1..{@code DRAW_COUNT_PURCHASE_MAX_QUANTITY}）
     * @throws com.betterschool.marketing.types.common.AppException 份数不合法抛 {@code LOTTERY_PARAM_ERROR}
     */
    DrawCountPurchaseOrderEntity createOrder(String userId, String token, int quantity);

    /**
     * 第三段（raffle 仓储侧）：次数入账（桶 {@code uk_user_reason_ref} 幂等，重放不重复加）
     * + 条件更新置 {@code DONE}（守卫 {@code state <> 1}，从 CREATED 与 DEAD 都可达）。
     * 纯幂等收敛动作，重试 / 重放安全。
     */
    void completeOrder(String purchaseOrderId, String userId, int quantity);

    /**
     * 积分不足等业务永久失败：条件更新置 {@code DEAD}（守卫 {@code state = 0}）。
     * DEAD 行不再被扫描，只被带原 token 的重放收敛（充值后重放即自愈）。
     */
    void markOrderDead(String purchaseOrderId);

    /** 扫描面：到期待收敛单（{@code state = 0} 且 {@code next_retry_at} 为空或已到点，FIFO），上限 {@code limit} */
    List<DrawCountPurchaseOrderEntity> listDueOrders(int limit);

    /**
     * 抢占式认领一笔待收敛单：条件更新（{@code state = 0} 且到点）递增 {@code retry_count}
     * 并按退避档（1/2/4/8/16 分钟）写 {@code next_retry_at}。
     * 返回 false = 已被其他实例抢先 / 状态漂移，跳过即可。
     */
    boolean claimDueOrder(DrawCountPurchaseOrderEntity order);

    /** 结果视图：购买单号 + 扣分 + 次数余额（余额现读未过期桶 SUM(remain)） */
    DrawCountPurchaseResultVO resultOf(DrawCountPurchaseOrderEntity order);
}
