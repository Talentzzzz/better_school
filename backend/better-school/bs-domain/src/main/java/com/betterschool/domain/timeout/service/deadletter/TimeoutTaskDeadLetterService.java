package com.betterschool.domain.timeout.service.deadletter;

import com.betterschool.domain.timeout.adapter.repository.ITimeoutRepository;
import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 死信管理领域服务实现：转发到仓储的条件 UPDATE——**裁决权仍然在库上**。
 * <p>
 * 三个写动作各自是单条语句、各自只打 FAILED 行，所以「并发两个管理员同时重发同一条」只有一次生效、
 * 「重发与取消抢同一条」也只是其中一个赢——不需要在这里加锁或先查后判。
 * <p>
 * 人工重发/取消打告警日志：这是「回调 16 次仍失败」之后的人工出口，值得在日志里留一笔
 * （谁动过死信、动了哪条），虽没有独立的操作流水表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeoutTaskDeadLetterService implements ITimeoutTaskDeadLetterService {

    private final ITimeoutRepository timeoutRepository;

    @Override
    public long countDeadLetters() {
        return timeoutRepository.countDeadLetters();
    }

    @Override
    public List<TimeoutTaskEntity> listDeadLetters(int offset, int limit) {
        return timeoutRepository.selectDeadLetters(offset, limit);
    }

    @Override
    public boolean resendDeadLetter(String taskType, String bizId) {
        boolean resent = timeoutRepository.resendDeadLetter(taskType, bizId);
        if (resent) {
            log.warn("[超时中心告警] 死信人工重发：taskType={}, bizId={}（retry_count 清零、立即重开一轮）",
                    taskType, bizId);
        }
        return resent;
    }

    @Override
    public boolean cancelDeadLetter(String taskType, String bizId) {
        boolean canceled = timeoutRepository.cancelDeadLetter(taskType, bizId);
        if (canceled) {
            log.warn("[超时中心告警] 死信人工取消：taskType={}, bizId={}", taskType, bizId);
        }
        return canceled;
    }
}
