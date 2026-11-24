package com.betterschool.infrastructure.timeout;

import lombok.Getter;

import java.util.Set;

/**
 * 一批任务的回调结果：只带<b>成功清单</b>。
 * <p>
 * 失败就是「抛了异常」，分发侧按项隔离（一项抛异常不拖垮同批其余项），失败项因此根本没有回报——
 * 不在成功清单里就是失败。少维护第二个集合就少一处自相矛盾。
 */
@Getter
public class CallbackReport {

    private final Set<TaskKey> successKeys;

    private CallbackReport(Set<TaskKey> successKeys) {
        this.successKeys = Set.copyOf(successKeys);
    }

    /** 无一项回报成功（未注册的 taskType、坏 bizId、业务状态机抛异常都归这里） */
    public static CallbackReport none() {
        return new CallbackReport(Set.of());
    }

    public static CallbackReport of(Set<TaskKey> successKeys) {
        return new CallbackReport(successKeys);
    }

    /**
     * 该行是否算「回调成功」——判据<b>从严</b>：必须出现在成功清单里。
     * 漏回报（异常、分发器不认识这个 taskType）算失败：重投一次无害，漏关一次是真事故。
     */
    public boolean isSucceeded(TaskKey key) {
        return successKeys.contains(key);
    }
}
