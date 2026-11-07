package cn.inventory.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 运行期指标快照（进程内累计，不落库）
 *
 * <p>压测/巡检时必须同时看两组数：
 * <ul>
 *   <li>{@code counters}：按结果状态累计的分布，用来对账「到底成了多少笔」；</li>
 *   <li>{@code mergePending}：合并器<b>当前</b>积压请求数（瞬时值）。
 *       压测中轮询它，能直接看出「消费跟不上到达」。</li>
 * </ul>
 * {@code errors} 是异常分桶（锁等待超时 / 死锁 / 池排队 / 驱动超时 / SQL 错误码），
 * 只记一个 ERROR 是没法判读的。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StockMetricsDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 结果状态 -> 累计次数 */
    private Map<String, Long> counters;

    /** 异常分桶 -> 累计次数（如 ERROR_LOCK_WAIT_TIMEOUT / ERROR_POOL_TIMEOUT） */
    private Map<String, Long> errors;

    /** 合并器当前积压请求数（瞬时值） */
    private Integer mergePending;
}
