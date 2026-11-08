package com.betterschool.integration.support;

import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.usecase.timeout.TimeoutTaskCallbackDispatcher;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进程内回调的测试夹具（超时中心内嵌化票 05）：测试侧顶替「中心」的角色。
 * <p>
 * 批量驱动器逐项打真分发器、逐项隔离——与生产扫描 case 的批内回调
 * （{@code TimeoutTaskScanCase.callback}）同口径：单项抛异常只记本项失败，不拖垮同批，
 * 返回失败项的 {@code taskType:bizId}。生产侧「一项失败、同批其余项照常结算」的钉子
 * 在 {@code TimeoutTaskScanIntegrationTest}（probe {@code failOnly}），不在这里。
 */
public final class TimeoutCallbackFixture {

    private TimeoutCallbackFixture() {
    }

    /** 造一条 {@code actionTime = minutesAgo 分钟前}（已到点）的任务，taskType/bizId 原样带上 */
    public static TimeoutTaskEntity dueTask(String taskType, String bizId, int minutesAgo) {
        TimeoutTaskEntity taskEntity = new TimeoutTaskEntity();
        taskEntity.setTaskType(taskType);
        taskEntity.setBizId(bizId);
        taskEntity.setActionTime(LocalDateTime.now().minusMinutes(minutesAgo));
        return taskEntity;
    }

    /** 逐项驱动真分发器并逐项隔离，返回失败项的 {@code taskType:bizId}（空集 = 全部成功） */
    public static Set<String> callback(TimeoutTaskCallbackDispatcher dispatcher, TimeoutTaskEntity... tasks) {
        Set<String> failures = new LinkedHashSet<>();
        for (TimeoutTaskEntity taskEntity : tasks) {
            try {
                dispatcher.callback(taskEntity);
            } catch (Exception e) {
                failures.add(taskEntity.getTaskType() + ":" + taskEntity.getBizId());
            }
        }
        return failures;
    }

    /** 全部成功（未命中状态机的幂等成功也算成功） */
    public static void assertCallbackSuccess(Set<String> failures) {
        assertThat(failures).as("回调应全部成功，失败项：%s", failures).isEmpty();
    }
}
