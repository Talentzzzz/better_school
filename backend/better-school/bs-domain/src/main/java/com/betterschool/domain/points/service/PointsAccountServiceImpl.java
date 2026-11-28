package com.betterschool.domain.points.service;

import com.betterschool.domain.points.adapter.repository.IPointsRepository;
import com.betterschool.domain.points.model.valobj.PointsDeductResultVO;
import com.betterschool.domain.points.model.valobj.PointsTransactionVO;
import com.betterschool.types.common.AppException;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.points.PointsReasonTypeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 桶模型积分账户（sql.md 积分段）：入账 = 插一行带过期时间的桶；消费 = 按 FIFO 扣各桶 remain。
 * <p>
 * 余额读口径：余额 = 未过期且未扣完的桶 remain 之和（SUM 读，没有余额列）——校园规模下
 * O(N) 的 SUM 完全够用，结构简单优先；积分恒不为负的保证在消费路径（加锁读桶并判不足）。
 * <p>
 * 事务：一次入账 / 一次消费的原子边界在仓储方法里（{@code insertBucketWithChanged} / {@code deduct}），
 * 本类只做入参校验与业务口径判断，不注入 {@code TransactionTemplate}。
 * 有效期是**本域自己的表**（points_validity_config，入账算 expire_at 的那次读在仓储事务内）；
 * 行为返利规则归返利域（action_rebate_config），由 case 层读取后把金额传进来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsAccountServiceImpl implements IPointsAccountService {

    private final IPointsRepository pointsRepository;

    @Override
    public void addForLotteryWin(String userId, String recordId, int pointsAmount) {
        if (pointsAmount <= 0) {
            // 非积分档（实物 / 谢谢参与）不该走到这里；真收到了也只是不加分，不抛异常打断 MQ 消费
            return;
        }
        if (!pointsRepository.insertBucketWithChanged(userId, pointsAmount, PointsReasonTypeEnum.LOTTERY_WIN, recordId)) {
            // 撞 uk = 这条中奖已入过账（MQ 重投）：不重复加分，也不重复发变动事件
            log.info("[PointsAccount] 中奖入账已存在，幂等跳过 userId={}, recordId={}", userId, recordId);
        }
    }

    @Override
    public boolean addForRebate(String userId, String refId, int amount) {
        if (userId == null || refId == null) {
            throw new AppException(ResponseCodeEnum.POINTS_PARAM_ERROR);
        }
        if (amount <= 0) {
            return false;
        }
        if (!pointsRepository.insertBucketWithChanged(userId, amount, PointsReasonTypeEnum.REBATE, refId)) {
            // 撞 uk = 这笔发放已记过（重试 / 重投）：不重复加分，也不重复发变动事件
            log.info("[PointsAccount] 返利入账已存在，幂等跳过 userId={}, refId={}", userId, refId);
        }
        return true;
    }

    @Override
    public PointsDeductResultVO deduct(String userId, int amount, PointsReasonTypeEnum pointsReasonType, String refId) {
        if (amount <= 0 || refId == null) {
            throw new AppException(ResponseCodeEnum.POINTS_PARAM_ERROR);
        }
        if (pointsReasonType != PointsReasonTypeEnum.REDEEM_DRAW_COUNT && pointsReasonType != PointsReasonTypeEnum.REDEEM_PRIZE) {
            throw new AppException(ResponseCodeEnum.POINTS_PARAM_ERROR, "不支持的积分消费原因");
        }
        return pointsRepository.deduct(userId, amount, pointsReasonType, refId);
    }

    @Override
    public int getBalance(String userId) {
        // 余额 = 未过期且未扣完的桶 remain 之和（读路径懒过期：过期桶被 expire_at > NOW() 滤掉）
        return pointsRepository.sumAvailableRemain(userId);
    }

    @Override
    public List<PointsTransactionVO> listTransactions(String userId, int page, int pageSize) {
        return pointsRepository.listTransactions(userId, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countTransactions(String userId) {
        return pointsRepository.countTransactions(userId);
    }

    @Override
    public int compactSmallBuckets() {
        return pointsRepository.compactSmallBuckets();
    }

    @Override
    public int getValidDays() {
        return pointsRepository.validDays();
    }

    @Override
    public void updateValidDays(int days) {
        if (days <= 0) {
            throw new AppException(ResponseCodeEnum.CONFIG_PARAM_ERROR, "有效期天数必须为正");
        }
        pointsRepository.updateValidDays(days);
    }
}
