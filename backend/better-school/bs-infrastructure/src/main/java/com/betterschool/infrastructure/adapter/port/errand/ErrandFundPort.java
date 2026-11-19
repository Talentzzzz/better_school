package com.betterschool.infrastructure.adapter.port.errand;

import com.betterschool.domain.credit.model.valobj.CreditReasonTypeVO;
import com.betterschool.domain.credit.service.ICreditAccountService;
import com.betterschool.domain.errand.adapter.port.IErrandFundPort;
import com.betterschool.domain.errand.adapter.repository.IErrandRepository;
import com.betterschool.domain.wallet.adapter.repository.IWalletRepository;
import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeVO;
import com.betterschool.infrastructure.tx.CompositeTxExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 跑腿资金收口端口适配器：一个 REQUIRED 物理事务（{@link CompositeTxExecutor}）内编排
 * 钱包域资金动作与跑腿域状态迁移（超时取消另含信用域扣减），各域仓储同为 REQUIRED 自动并入——
 * 「订单对外可见（待支付 → 已发布）⇒ 悬赏已冻结托管」「订单已取消 ⇒ 悬赏已解冻回余额」
 * 由物理事务保证，中间态无处存活。
 * <p>
 * 为什么在本适配器而不是 case 层或某一域：跑腿域不写钱包表、钱包域不写跑腿表（域边界），
 * case 层不包多域事务（仓库纪律）；跨域资金一致性由基础设施层端口收口——
 * 营销侧 {@code PointsDeductPort} 是同一形状的先例。
 * <p>
 * 编排内各步均为既有 REQUIRED 原子用例，仅在此并入同一物理事务：
 * 冻结 = 流水先行（{@code uk_order_type} 幂等）+ 可用余额条件 UPDATE（不足抛异常整事务回滚）；
 * 解冻 = 流水先行（同键幂等）+ 冻结额回退；
 * 翻转 = 条件 UPDATE（守卫前态）+ 事件行 + feed 索引行（+ 搜索同步 outbox 行）。
 */
@Component
@RequiredArgsConstructor
public class ErrandFundPort implements IErrandFundPort {

    private final CompositeTxExecutor compositeTxExecutor;
    private final IWalletRepository walletRepository;
    private final IErrandRepository errandRepository;
    /** 信用域变更入口（applyChange = 流水先行 + uk_user_reason_ref 幂等的唯一口径，与 case 层同用） */
    private final ICreditAccountService creditAccountService;

    @Override
    public void payWithFundFreeze(String orderId, String publisherId, BigDecimal reward) {
        compositeTxExecutor.execute(action -> {
            // 1+2. 冻结悬赏：流水先行（uk_order_type 冲突 = 已冻结过，幂等跳过不动余额）
            //      + 可用余额（balance - frozen）条件扣减，不足抛异常 → 整事务回滚、订单留待支付
            walletRepository.freeze(publisherId, reward, orderId, WalletDetailTypeVO.FROZEN);
            // 3+4. 订单「待支付 → 已发布」条件翻转 + bs-errand-paid outbox 行 + feed 索引行诞生
            //      （payById 0 行抛异常，冻结随之回滚——状态翻转跟着钱走）
            errandRepository.payWithPaidMessage(orderId, publisherId);
            return null;
        });
    }

    @Override
    public void cancelWithRefund(String orderId, String publisherId, BigDecimal reward) {
        compositeTxExecutor.execute(action -> {
            // 1. 解冻退款：流水先行（UNFROZEN，uk_order_type 冲突 = 已解冻过，幂等跳过不动冻结额）
            //    + 冻结额回退——钱先回可用余额
            walletRepository.unfreeze(publisherId, reward, orderId, WalletDetailTypeVO.UNFROZEN);
            // 2. 订单取消条件翻转（守卫前态）+ CANCELLED 事件行 + feed 索引不可见化 + 搜索同步 outbox 行
            //    （翻转 0 行抛异常，解冻随之回滚——钱跟着订单状态走）
            errandRepository.cancelByPublisherWithSearchSync(orderId, publisherId);
            return null;
        });
    }

    @Override
    public void timeoutCancelWithRefund(String orderId, String publisherId, String takerId,
                                        BigDecimal reward, boolean committed) {
        compositeTxExecutor.execute(action -> {
            // 1. 解冻退款：流水先行（UNFROZEN，uk_order_type 冲突 = 已解冻过，幂等跳过不动冻结额）
            //    + 冻结额回退——钱先回可用余额
            walletRepository.unfreeze(publisherId, reward, orderId, WalletDetailTypeVO.UNFROZEN);
            // 2. 开工场景接单方信用分扣减：流水先行（uk_user_reason_ref 冲突 = 已扣过，幂等跳过不动分数）
            //    + 分数/封禁联动（信用域仓储 REQUIRED 自动并入同一物理事务）
            if (committed) {
                creditAccountService.applyChange(takerId, CreditReasonTypeVO.ERRAND_TIMEOUT, orderId);
            }
            // 3. 订单超时取消条件翻转（state + deadline 双守卫）+ CANCELLED 事件行 + feed 索引不可见化
            //    + 搜索同步 outbox 行（翻转 0 行 = 并发重复回调或时钟偏差 → 抛异常按失败重投，
            //    解冻与扣分随之回滚——钱和分都跟着订单状态走）
            if (!errandRepository.markTimeoutWithSearchSync(orderId)) {
                throw new IllegalStateException("订单超时回调条件 UPDATE 未命中，按失败重投：orderId=" + orderId);
            }
            return null;
        });
    }

    @Override
    public void completeWithIncome(String orderId, String publisherId, String takerId, BigDecimal reward) {
        compositeTxExecutor.execute(action -> {
            // 1. 发布方解冻退款：流水先行（UNFROZEN，uk_order_type 冲突 = 已解冻过，幂等跳过不动冻结额）
            //    + 冻结额回退——托管悬赏先回发布方可用余额
            walletRepository.unfreeze(publisherId, reward, orderId, WalletDetailTypeVO.UNFROZEN);
            // 2. 接单方悬赏收入：流水先行（INCOME，uk_order_type 冲突 = 已入账过，幂等跳过不动余额）
            //    + 余额入账——与确认收货同一结算口径（接单方得悬赏总额）
            walletRepository.income(takerId, reward, orderId, WalletDetailTypeVO.INCOME);
            // 3. 订单「已送达 → 已完成」条件翻转（守卫 DELIVERED）+ AUTO_COMPLETED 事件行
            //    + feed 索引完成化 + 搜索同步 outbox 行（翻转 0 行 = 并发重复回调 → 抛异常按失败重投，
            //    解冻与入账随之回滚——钱跟着订单状态走）
            if (!errandRepository.autoCompleteWithSearchSync(orderId)) {
                throw new IllegalStateException("订单自动完成条件 UPDATE 未命中，按失败重投：orderId=" + orderId);
            }
            return null;
        });
    }
}
