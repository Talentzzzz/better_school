package com.betterschool.domain.moderation.adapter.port;

/**
 * 审核派单可观测计数端口（票 11，spec §9）：累计领单次数与租约超时回收次数，两者之比即租约超时率。
 * 计数落 Redis（spec「DB 权证，Redis 只做信号/计数」——计数非资金非真相源，重启丢数＝少计＝观测低估，
 * 方向安全）；主动放回不计（那是审核员主动行为，不是超时）
 */
public interface IReviewDispatchMetricsPort {

    /** 领单成功 +1（领单事务提交后调用，回滚不计） */
    void recordClaim();

    /** 租约超时回收 +n（回收事务提交后调用；n 为批量惰性回收条数，常规回调回收传 1） */
    void recordLeaseTimeout(int count);

    /** 计数快照：累计领单数与累计租约超时回收数（观测 job 取比值用；值对象替代魔法下标） */
    record Snapshot(long claimCount, long leaseTimeoutCount) {
    }

    /** 快照：进程生命周期累计的两项计数 */
    Snapshot snapshot();
}
