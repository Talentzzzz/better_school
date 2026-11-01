package com.betterschool.domain.moderation.model.valobj;

/**
 * 待审等待统计（票 11 可观测口径，spec §9）：工单池 PENDING 行的等待时长画像——
 * 池内条数（队列深度总量）、平均等待秒、最老等待秒（entered_at 起算，回收不改 entered_at、
 * 等待时长持续累加）。只读派生值，不落列、不持久化；积压告警的「最老待审等待时长」阈值以它为据
 */
public record ReviewPendingWaitStats(long pendingCount, long avgWaitSeconds, long oldestWaitSeconds) {
}
