package com.betterschool.usecase.post;

import com.betterschool.domain.points.service.IPointsAccountService;
import com.betterschool.domain.post.adapter.repository.IPostRepository;
import com.betterschool.domain.raffle.service.drawcount.IDrawCountAccountService;
import com.betterschool.domain.rebate.adapter.repository.IRebateRepository;
import com.betterschool.domain.rebate.model.valobj.RebateClaimResultVO;
import com.betterschool.domain.rebate.service.IRebateService;
import com.betterschool.types.common.PostRebateClaimTaskMessage;
import com.betterschool.types.common.PostRebateCreditMessage;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import com.betterschool.types.rebate.RebateActionType;
import com.betterschool.types.rebate.RebatePeriodKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 发帖返利用例编排：同步领取 + 领取任务重放 + 异步入账。
 * <p>
 * 两段式流程：
 * <ol>
 *   <li>同步领取：发帖事务提交后，请求线程调返利域原子抢占当天名额并快照金额，结果随发帖响应返回；</li>
 *   <li>异步入账：CLAIMED 的领取事务内已落 credit outbox，credit 消费者按快照给积分桶 / 抽奖次数桶入账，
 *       两者都完成后把领取记录标记 GRANTED。</li>
 * </ol>
 * 跨域不开事务（ADR-0016）：各域方法自身事务原子，失败靠唯一键幂等 + broker 重试收敛。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostRebateCase {

    private final IRebateService rebateService;
    private final IRebateRepository rebateRepository;
    private final IPostRepository postRepository;
    private final IPointsAccountService pointsAccountService;
    private final IDrawCountAccountService drawCountAccountService;

    /**
     * 同步领取：periodKey 由帖子发布时间（Asia/Shanghai）格式化；正常返回后把领取任务标记已处理。
     * <p>
     * 领取异常直接上抛，由 {@link PostPublishCase} 转成 PENDING；标记失败只告警——任务留 PENDING，
     * outboxScan 重放时领取按 ref 幂等，不会重复发。
     */
    public RebateClaimResultVO claimForPost(String userId, String postId, LocalDateTime publishTime) {
        RebateClaimResultVO result = rebateService.claimActionRebate(
                RebateActionType.POST.getCode(), userId, postId, RebatePeriodKeys.of(publishTime));
        markClaimTaskProcessed(postId);
        return result;
    }

    /**
     * 领取任务重放（claim consumer 调用）：periodKey 缺失（旧载荷）按当天兜底；领取后标记任务已处理。
     * <p>
     * 领取异常上抛交 broker 重试；领取按 {@code (userId, actionType, refId)} 幂等。
     */
    public void replayClaim(PostRebateClaimTaskMessage message) {
        String periodKey = (message.getPeriodKey() == null || message.getPeriodKey().isBlank())
                ? RebatePeriodKeys.today()
                : message.getPeriodKey();
        rebateService.claimActionRebate(
                RebateActionType.POST.getCode(), message.getUserId(), message.getPostId(), periodKey);
        markClaimTaskProcessed(message.getPostId());
    }

    /**
     * 入账编排（credit consumer 调用）：积分 / 抽奖次数各自按领取快照金额入账（> 0 才调），
     * 全部完成后把领取记录标记 GRANTED。
     * <p>
     * 异常上抛交 broker 重试；两个桶的唯一键 {@code (userId, REBATE, postId)} 吸收重复，
     * 单边失败重试不会重复发放；{@code markGranted} 条件 UPDATE 幂等。
     */
    public void credit(PostRebateCreditMessage message) {
        if (message == null || message.getClaimId() == null
                || message.getUserId() == null || message.getPostId() == null) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "发帖返利入账消息缺少必要参数");
        }
        if (message.getPointsValue() > 0) {
            pointsAccountService.creditRebate(
                    message.getUserId(), message.getPointsValue(), message.getPostId());
        }
        if (message.getDrawCountValue() > 0) {
            drawCountAccountService.creditRebate(
                    message.getUserId(), message.getDrawCountValue(), message.getPostId());
        }
        rebateRepository.markGranted(message.getClaimId());
    }

    /** 领取任务标记已处理：失败只告警，不影响领取结果（任务留 PENDING 由 outboxScan 重放，重放幂等） */
    private void markClaimTaskProcessed(String postId) {
        try {
            postRepository.markRebateClaimTaskProcessed(postId);
        } catch (Exception e) {
            log.warn("标记发帖返利领取任务已处理失败，留待 outboxScan 重放，postId={}", postId, e);
        }
    }
}
