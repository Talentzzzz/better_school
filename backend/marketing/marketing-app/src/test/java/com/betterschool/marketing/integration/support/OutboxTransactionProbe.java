package com.betterschool.marketing.integration.support;

import com.betterschool.marketing.domain.raffle.adapter.repository.IRaffleRepository;
import com.betterschool.marketing.domain.raffle.model.aggregate.DrawRecordAggregate;
import com.betterschool.marketing.domain.raffle.model.entity.DrawRecordDetailEntity;
import com.betterschool.marketing.domain.raffle.model.entity.DrawRecordEntity;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawRecordActionVO;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawRecordStateVO;
import com.betterschool.marketing.types.common.LotteryDrawnMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 「outbox 与业务写同事务」的验证探针（票 14 验收 2）：在**一个事务**里落抽奖记录 + 事件，
 * 然后按开关抛异常模拟业务事务后半段失败。
 * <p>
 * 为什么需要它：事务边界本身没有外部可观察面——只有「事后异常 → 记录与事件一起消失」
 * 才能证明事件行确实挂在业务事务里（而不是提交后补写）。仓储的事务是 REQUIRED 传播，
 * 嵌在探针事务里；探针抛异常时内层已置 rollback-only，外层提交即回滚，两处写入同时消失。
 */
@Component
@RequiredArgsConstructor
public class OutboxTransactionProbe {

    private final IRaffleRepository raffleRepository;
    private final TransactionTemplate transactionTemplate;

    /** 同事务落 draw_record + DRAW 明细 + outbox 事件（不抛异常：正常路径） */
    public void insertThenCommit(String userId, String recordId) {
        insert(userId, recordId, false);
    }

    /** 同事务落三处后抛异常：验证记录与事件一起回滚 */
    public void insertThenFail(String userId, String recordId) {
        insert(userId, recordId, true);
    }

    private void insert(String userId, String recordId, boolean failAfterwards) {
        transactionTemplate.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();

            DrawRecordEntity record = new DrawRecordEntity();
            record.setRecordId(recordId);
            record.setUserId(userId);
            record.setActivityId("00000000-0000-4000-8000-000000000001");
            record.setStrategyId("00000000-0000-4000-8000-000000000001");
            record.setAwardId("00000000-0000-4000-8000-000000000001");
            record.setAwardName("探针奖品");
            record.setAwardType("VIRTUAL");
            record.setLevel(1);
            record.setInvId("00000000-0000-4000-8000-000000000999");
            record.setState(DrawRecordStateVO.PENDING);
            record.setCreateTime(now);
            record.setUpdateTime(now);

            DrawRecordDetailEntity detail = new DrawRecordDetailEntity();
            detail.setUserId(userId);
            detail.setRecordId(recordId);
            detail.setType(DrawRecordActionVO.DRAW.getCode());
            detail.setCreateTime(now);
            detail.setUpdateTime(now);

            DrawRecordAggregate aggregate = new DrawRecordAggregate();
            aggregate.setRecord(record);
            aggregate.setDetail(detail);
            aggregate.setDrawnEvent(new LotteryDrawnMessage(recordId, userId, "00000000-0000-4000-8000-000000000001", "VIRTUAL", 20));
            raffleRepository.insertDrawRecord(aggregate);

            if (failAfterwards) {
                throw new IllegalStateException("探针：业务事务后半段失败");
            }
        });
    }
}
