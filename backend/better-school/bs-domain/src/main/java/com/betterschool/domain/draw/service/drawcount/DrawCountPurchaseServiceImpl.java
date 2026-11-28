package com.betterschool.domain.draw.service.drawcount;

import com.betterschool.domain.draw.adapter.repository.IDrawRepository;
import com.betterschool.domain.draw.model.entity.DrawCountPurchaseOrderEntity;
import com.betterschool.domain.draw.model.valobj.DrawCountPurchaseResultVO;
import com.betterschool.types.common.AppException;
import com.betterschool.types.common.IdempotencyKeys;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.draw.Constants;
import com.betterschool.types.draw.DrawCountPurchaseStateEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 购买单的抽奖域面实现（ADR-0016 票 C 三段式的 draw 侧）：只做单据状态机——
 * 落单 {@code CREATED}（本地事务）、入账置 {@code DONE}、业务失败置 {@code DEAD}、
 * 扫描认领（退避写在本类）。扣积分是**积分域**的动作，编排归 case 层
 * {@code DrawCase}（事务边界 = 仓储，本类不注入他域服务与事务模板）。
 * <p>
 * 「单在」从此只意味着「扣分结果未知」（ADR-0014 的「单在 ⇒ 分已扣」被 ADR-0016 降级）：
 * 重放 / 扫描统一做「扣分（流水唯一键幂等）→ 入账（桶唯一键幂等）→ 置 DONE（条件更新）」，
 * CREATED 而无扣分流水与有流水而未 DONE 两条分叉被三层幂等吸收，无需先判流水。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DrawCountPurchaseServiceImpl implements IDrawCountPurchaseService {

    /** 第 n 次尝试失败后的退避档（1/2/4/8/16 分钟），由认领动作写进 next_retry_at */
    private static final List<Duration> COMPENSATE_BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4),
            Duration.ofMinutes(8), Duration.ofMinutes(16));

    private final IDrawCountAccountService drawCountAccountService;
    private final IDrawRepository drawRepository;

    @Override
    public DrawCountPurchaseOrderEntity createOrder(String userId, String token, int quantity) {
        if (token == null || token.isBlank() || quantity <= 0
                || quantity > Constants.DRAW_COUNT_PURCHASE_MAX_QUANTITY) {
            throw new AppException(ResponseCodeEnum.LOTTERY_PARAM_ERROR);
        }
        String purchaseOrderId = IdempotencyKeys.of(userId, token);
        int pointsCost = quantity * Constants.DRAW_COUNT_PURCHASE_PRICE;

        // Stripe 幂等入口：同 token 重放先查单——存在即返回原单，推进由 case 层按状态裁决
        DrawCountPurchaseOrderEntity existing = drawRepository.queryPurchaseOrder(purchaseOrderId);
        if (existing != null) {
            return existing;
        }
        DrawCountPurchaseOrderEntity order = new DrawCountPurchaseOrderEntity(
                null, purchaseOrderId, userId, quantity, pointsCost,
                DrawCountPurchaseStateEnum.CREATED, 0, null, null, null, null);
        try {
            // 第一段：购买单 INSERT，draw 仓储本地事务（不再并入扣分事务，ADR-0016）
            drawRepository.insertPurchaseOrder(order);
        } catch (DuplicateKeyException e) {
            // 并发同 token：另一请求已落单（唯一键是唯一互斥点），转幂等推进
            log.info("[DrawCountPurchase] 并发同 token 撞购买单唯一键，转幂等推进 purchaseOrderId={}",
                    purchaseOrderId);
            existing = drawRepository.queryPurchaseOrder(purchaseOrderId);
            if (existing == null) {
                throw new AppException(ResponseCodeEnum.SYSTEM_ERROR);
            }
            return existing;
        }
        return order;
    }

    @Override
    public void completeOrder(String purchaseOrderId, String userId, int quantity) {
        drawCountAccountService.addForPurchase(userId, purchaseOrderId, quantity);
        drawRepository.finishPurchaseOrder(purchaseOrderId);
    }

    @Override
    public void markOrderDead(String purchaseOrderId) {
        drawRepository.markPurchaseOrderDead(purchaseOrderId);
    }

    @Override
    public List<DrawCountPurchaseOrderEntity> listDueOrders(int limit) {
        return drawRepository.listDuePurchaseOrders(limit);
    }

    @Override
    public boolean claimDueOrder(DrawCountPurchaseOrderEntity drawCountPurchaseOrderEntity) {
        return drawRepository.claimPurchaseOrder(drawCountPurchaseOrderEntity.getId(), nextBackoffAt(drawCountPurchaseOrderEntity.getRetryCount()));
    }

    @Override
    public DrawCountPurchaseResultVO resultOf(DrawCountPurchaseOrderEntity drawCountPurchaseOrderEntity) {
        return new DrawCountPurchaseResultVO(drawCountPurchaseOrderEntity.getPurchaseOrderId(), drawCountPurchaseOrderEntity.getPointsCost(),
                drawCountAccountService.getBalance(drawCountPurchaseOrderEntity.getUserId()));
    }

    /** 第 retryCount+1 次尝试的退避档下标（封顶最后一档 16 分钟） */
    private LocalDateTime nextBackoffAt(Integer retryCount) {
        int index = Math.min(retryCount == null ? 0 : retryCount, COMPENSATE_BACKOFF.size() - 1);
        return LocalDateTime.now().plus(COMPENSATE_BACKOFF.get(Math.max(index, 0)));
    }
}
