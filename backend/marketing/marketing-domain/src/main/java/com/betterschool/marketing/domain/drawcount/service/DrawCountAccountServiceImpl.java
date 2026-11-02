package com.betterschool.marketing.domain.drawcount.service;

import com.betterschool.marketing.domain.config.model.valobj.RebateTypeVO;
import com.betterschool.marketing.domain.drawcount.adapter.port.config.IDrawCountConfigPort;
import com.betterschool.marketing.domain.drawcount.adapter.repository.IDrawCountRepository;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.drawcount.DrawCountReasonType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 桶模型抽奖次数账户（与积分域同构）：入账 = 插一行带过期时间的桶；
 * 扣次 = 按 FIFO 扣各桶 remain、每扣 1 次落一行明细（无 amount 列，行即 1 次）。
 * <p>
 * 事务：一次入账 / 一次扣次的原子边界在仓储方法里（{@code creditBucket} / {@code deductForDraw}），
 * 本类只做入参校验与业务口径判断，不注入 {@code TransactionTemplate}。
 * 有效期天数与返利规则是配置域的表（draw_count_config / action_rebate_config），
 * 经 {@link IDrawCountConfigPort} 只读（注意：入账算 expire_at 的那次读在仓储事务内，
 * 由仓储实现注入同一端口完成）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DrawCountAccountServiceImpl implements IDrawCountAccountService {

    private final IDrawCountRepository drawCountRepository;
    private final IDrawCountConfigPort drawCountConfigPort;

    @Override
    public boolean creditForRedeem(String userId, String redeemOrderId, int amount) {
        if (userId == null || redeemOrderId == null || amount <= 0) {
            throw new AppException(ResponseCode.LOTTERY_PARAM_ERROR);
        }
        return credit(userId, amount, DrawCountReasonType.REDEEM, redeemOrderId);
    }

    @Override
    public boolean creditForRebate(String actionType, String userId, String refId) {
        if (actionType == null || actionType.isBlank() || userId == null || refId == null) {
            throw new AppException(ResponseCode.LOTTERY_PARAM_ERROR);
        }
        Integer rebateValue = drawCountConfigPort.findOpenRebateValue(actionType, RebateTypeVO.DRAW_COUNT);
        if (rebateValue == null) {
            return false;
        }
        return credit(userId, rebateValue, DrawCountReasonType.REBATE, refId);
    }

    @Override
    public int getBalance(String userId) {
        return drawCountRepository.sumAvailableRemain(userId);
    }

    @Override
    public boolean deductForDraw(String userId, String recordId) {
        if (userId == null || recordId == null) {
            throw new AppException(ResponseCode.LOTTERY_PARAM_ERROR);
        }
        return drawCountRepository.deductForDraw(userId, recordId);
    }

    @Override
    public int expireBuckets() {
        return drawCountRepository.expireBuckets();
    }

    /** 入账共享体：REDEEM / REBATE 共用一条路径，幂等键落在 uk_user_reason_ref 上 */
    private boolean credit(String userId, int amount, DrawCountReasonType reasonType, String refId) {
        if (!drawCountRepository.creditBucket(userId, amount, reasonType, refId)) {
            // 撞 uk = 这笔发放已记过（重试 / 重投）：不重复加次数
            log.info("[DrawCountAccount] 次数入账已存在，幂等跳过 userId={}, reasonType={}, refId={}",
                    userId, reasonType, refId);
        }
        return true;
    }
}
