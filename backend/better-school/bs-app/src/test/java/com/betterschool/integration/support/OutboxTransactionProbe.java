package com.betterschool.integration.support;

import com.betterschool.domain.draw.adapter.repository.IDrawRepository;
import com.betterschool.domain.draw.model.aggregate.DrawRecordAggregate;
import com.betterschool.domain.draw.model.entity.DrawRecordDetailEntity;
import com.betterschool.domain.draw.model.entity.DrawRecordEntity;
import com.betterschool.domain.draw.model.valobj.DrawRecordActionEnum;
import com.betterschool.domain.draw.model.valobj.DrawRecordStateVO;
import com.betterschool.types.common.LotteryDrawnMessage;
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

    private final IDrawRepository drawRepository;
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

            DrawRecordEntity drawRecordEntity = new DrawRecordEntity();
            drawRecordEntity.setRecordId(recordId);
            drawRecordEntity.setUserId(userId);
            drawRecordEntity.setActivityId("00000000-0000-4000-8000-000000000001");
            drawRecordEntity.setStrategyId("00000000-0000-4000-8000-000000000001");
            drawRecordEntity.setAwardId("00000000-0000-4000-8000-000000000001");
            drawRecordEntity.setAwardName("探针奖品");
            drawRecordEntity.setAwardType("VIRTUAL");
            drawRecordEntity.setLevel(1);
            drawRecordEntity.setInvId("00000000-0000-4000-8000-000000000999");
            drawRecordEntity.setState(DrawRecordStateVO.PENDING);
            drawRecordEntity.setCreateTime(now);
            drawRecordEntity.setUpdateTime(now);

            DrawRecordDetailEntity drawRecordDetailEntity = new DrawRecordDetailEntity();
            drawRecordDetailEntity.setUserId(userId);
            drawRecordDetailEntity.setRecordId(recordId);
            drawRecordDetailEntity.setType(DrawRecordActionEnum.DRAW.getCode());
            drawRecordDetailEntity.setCreateTime(now);
            drawRecordDetailEntity.setUpdateTime(now);

            DrawRecordAggregate drawRecordAggregate = new DrawRecordAggregate();
            drawRecordAggregate.setRecord(drawRecordEntity);
            drawRecordAggregate.setDetail(drawRecordDetailEntity);
            drawRecordAggregate.setDrawnEvent(new LotteryDrawnMessage(recordId, userId, "00000000-0000-4000-8000-000000000001", "VIRTUAL", 20));
            drawRepository.insertDrawRecord(drawRecordAggregate);

            if (failAfterwards) {
                throw new IllegalStateException("探针：业务事务后半段失败");
            }
        });
    }
}
