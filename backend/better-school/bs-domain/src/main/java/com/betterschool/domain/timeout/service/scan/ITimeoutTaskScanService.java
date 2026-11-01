package com.betterschool.domain.timeout.service.scan;

import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.domain.timeout.model.valobj.CallbackReportVO;
import com.betterschool.domain.timeout.model.valobj.SettleOutcomeVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 扫描 / 结算领域服务（超时中心内嵌化票 04，照原 {@code TimeoutTaskScanService} 口径搬入本工程）。
 * <p>
 * 分工：捞批与结算的<b>判据</b>在本域服务（退避公式、死信上限、分桶），路由与语句形状在仓储
 * （槽位 → hint）；用例层只负责「认领哪些槽位、用多大并行度、组织回调」。
 */
public interface ITimeoutTaskScanService {

    /**
     * 捞一个槽位上「未终态且已到点」的一批任务。
     *
     * @param slot      槽位号（0..TOTAL-1，由分片 job 的 shardIndex/shardTotal 认领得到）
     * @param dueBefore 本次 tick 固定的到期判据（<b>不是</b>每批现取 now，见用例层注释）
     * @param limit     单批上限
     * @return 捞到的行（带 {@code shardSlot}，供结算批量语句塞 hint）
     */
    List<TimeoutTaskEntity> fetchDueBatch(int slot, LocalDateTime dueBefore, int limit);

    /**
     * 结算一批：按「回调结果 × taskType」分桶，每桶一条批量条件 UPDATE——
     * 成功置 SUCCESS 终态、失败进 RETRYING（按行算退避）、到上限的进 FAILED 死信。
     * <p>
     * 三条语句都保留 {@code status IN ('WAITING','RETRYING')} 行级守卫：捞批与结算之间的窗口里
     * 被取消/被死信重发的行不会被回调结果翻回来——裁决权在库上。
     * 结算覆盖批内每一行（三项之和 = 批大小）。
     */
    SettleOutcomeVO settle(List<TimeoutTaskEntity> batch, CallbackReportVO report);
}
