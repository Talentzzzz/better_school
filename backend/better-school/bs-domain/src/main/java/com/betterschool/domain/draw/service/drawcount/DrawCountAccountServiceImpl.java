package com.betterschool.domain.draw.service.drawcount;

import com.betterschool.domain.draw.adapter.repository.IDrawRepository;
import com.betterschool.types.common.AppException;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.draw.DrawCountReasonTypeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 桶模型抽奖次数账户（与积分桶同构）：入账 = 插一行带过期时间的桶；
 * 扣次 = 按 FIFO 扣各桶 remain、每扣 1 次落一行明细（无 amount 列，行即 1 次）。
 * <p>
 * 余额读口径：剩余次数 = 未过期且未扣完的桶 remain 之和（SUM 读，没有余额列，与积分侧同构）。
 * <p>
 * 事务：一次入账 / 一次扣次的原子边界在仓储方法里（{@code addDrawCountBucket} /
 * {@code deductDrawCountForDraw}），本类只做入参校验与业务口径判断，不注入 {@code TransactionTemplate}。
 * 有效期是**域内自己的表**（draw_count_config，入账算 expire_at 的那次读在仓储事务内）；
 * 行为返利规则归返利域（action_rebate_config），由 case 层读取后把金额传进来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DrawCountAccountServiceImpl implements IDrawCountAccountService {

    private final IDrawRepository drawRepository;

    @Override
    public boolean addForPurchase(String userId, String purchaseOrderId, int amount) {
        if (userId == null || purchaseOrderId == null || amount <= 0) {
            throw new AppException(ResponseCodeEnum.LOTTERY_PARAM_ERROR);
        }
        return addBucket(userId, amount, DrawCountReasonTypeEnum.PURCHASE, purchaseOrderId);
    }

    @Override
    public boolean addForRebate(String userId, String refId, int amount) {
        if (userId == null || refId == null) {
            throw new AppException(ResponseCodeEnum.LOTTERY_PARAM_ERROR);
        }
        if (amount <= 0) {
            return false;
        }
        return addBucket(userId, amount, DrawCountReasonTypeEnum.REBATE, refId);
    }

    @Override
    public int getBalance(String userId) {
        // 剩余次数 = 未过期且未扣完的桶 remain 之和（读路径懒过期：过期桶被 expire_at > NOW() 滤掉）
        return drawRepository.sumAvailableDrawCount(userId);
    }

    @Override
    public boolean deductForDraw(String userId, String recordId) {
        if (userId == null || recordId == null) {
            throw new AppException(ResponseCodeEnum.LOTTERY_PARAM_ERROR);
        }
        return drawRepository.deductDrawCountForDraw(userId, recordId);
    }

    @Override
    public void refundForOrphanDraw(String userId, String recordId, int amount) {
        if (userId == null || recordId == null || amount <= 0) {
            throw new AppException(ResponseCodeEnum.LOTTERY_PARAM_ERROR);
        }
        addBucket(userId, amount, DrawCountReasonTypeEnum.ORPHAN_REFUND, recordId);
    }

    @Override
    public int getValidDays() {
        return drawRepository.drawCountValidDays();
    }

    @Override
    public void updateValidDays(int days) {
        if (days <= 0) {
            throw new AppException(ResponseCodeEnum.CONFIG_PARAM_ERROR, "有效期天数必须为正");
        }
        drawRepository.updateDrawCountValidDays(days);
    }

    /** 入账共享体：REDEEM / REBATE 共用一条路径，幂等键落在 uk_user_reason_ref 上 */
    private boolean addBucket(String userId, int amount, DrawCountReasonTypeEnum drawCountReasonType, String refId) {
        if (!drawRepository.addDrawCountBucket(userId, amount, drawCountReasonType, refId)) {
            // 撞 uk = 这笔发放已记过（重试 / 重投）：不重复加次数
            log.info("[DrawCountAccount] 次数入账已存在，幂等跳过 userId={}, reasonType={}, refId={}",
                    userId, drawCountReasonType, refId);
        }
        return true;
    }
}
