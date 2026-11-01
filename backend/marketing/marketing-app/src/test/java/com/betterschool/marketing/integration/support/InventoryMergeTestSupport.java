package com.betterschool.marketing.integration.support;

import com.betterschool.marketing.infrastructure.inventory.merge.QuotaMergeBuffer;

/**
 * 合并用例的公共夹具（票 08 改写）：旧合并机制（时间格 / 兜底 / 消息）已整条删除，
 * 这里只剩「等待进程内合并器把缓冲排空」一件事——库存侧两个 {@code submitAndWait} 都等结果，
 * 用例返回时事务已提交；本工具给守恒 / 对账断言一个「缓冲确实排空了」的稳定观察点，
 * 避免 B2 / B3 读台账时还有在途 flush。
 */
public final class InventoryMergeTestSupport {

    private InventoryMergeTestSupport() {
    }

    /** 等待合并器排空（缓冲清零且无在途 flush）；超时返回 false，用例据此快速失败 */
    public static boolean awaitDrained(QuotaMergeBuffer buffer, long timeoutMs) {
        return buffer.awaitIdle(timeoutMs);
    }

    /** 当前积压件数（含在途 flush）：非 0 即还有件没落库 */
    public static int pendingOf(QuotaMergeBuffer buffer) {
        return buffer.pending();
    }
}
