package com.betterschool.domain.timeout.model.valobj;

import lombok.Getter;

import java.util.Set;

/**
 * 一批任务的回调结果：只带**成功清单**（超时中心内嵌化票 04）。
 * <p>
 * 与独立中心时代的双清单（成功 + 失败）不同：进程内分发后失败就是「抛了异常」，
 * 分发侧按项隔离（一项抛异常不拖垮同批其余项），失败项因此根本没有回报——
 * 不在成功清单里就是失败，与旧口径「两边都没回报的项按失败论」一致，
 * 少维护第二个集合就少一处自相矛盾（旧 {@code isSucceeded} 要同时看两个清单）。
 */
@Getter
public class CallbackReportVO {

    private final Set<TaskKeyVO> successKeys;

    private CallbackReportVO(Set<TaskKeyVO> successKeys) {
        this.successKeys = Set.copyOf(successKeys);
    }

    /** 无一项回报成功（未注册的 taskType、坏 bizId、业务状态机抛异常都归这里） */
    public static CallbackReportVO none() {
        return new CallbackReportVO(Set.of());
    }

    public static CallbackReportVO of(Set<TaskKeyVO> successKeys) {
        return new CallbackReportVO(successKeys);
    }

    /**
     * 该行是否算「回调成功」——判据**从严**：必须出现在成功清单里。
     * 漏回报（异常、进程内分发不认识这个 taskType）算失败：重投一次无害，漏关一次是真事故。
     */
    public boolean isSucceeded(TaskKeyVO key) {
        return successKeys.contains(key);
    }
}
