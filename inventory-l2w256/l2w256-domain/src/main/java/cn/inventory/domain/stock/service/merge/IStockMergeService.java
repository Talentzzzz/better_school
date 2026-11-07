package cn.inventory.domain.stock.service.merge;

import cn.inventory.domain.stock.model.valobj.DeductRequestVO;
import cn.inventory.domain.stock.model.valobj.DeductResultVO;

/**
 * 合并扣减能力（L2W256）
 *
 * <p>对外只暴露三件事：提交一笔、看积压、等排空。
 * 排空（{@link #awaitIdle}）是给「重置库存」和「停机」用的：
 * 上一轮的尾批若在新一轮开始后落库，会把本轮起始库存改掉，整轮数据作废。
 */
public interface IStockMergeService {

    /**
     * 提交一笔扣减并等待结果。
     *
     * @return 结果状态；{@code TIMEOUT_UNKNOWN} 表示「已排队但还没落库」，<b>不是失败</b>
     */
    DeductResultVO submit(DeductRequestVO request);

    /** 当前积压请求数（瞬时值，无锁读） */
    int pendingCount();

    /**
     * 等待合并器排空（所有缓冲区空、所有在途 flush 结束）。
     *
     * @return true = 已排空；false = 超时（调用方应记录告警，不要静默继续）
     */
    boolean awaitIdle(long timeoutMs);

    /** 同上，等待上限取配置的 {@code merge.drain-timeout-ms} */
    boolean awaitIdle();
}
