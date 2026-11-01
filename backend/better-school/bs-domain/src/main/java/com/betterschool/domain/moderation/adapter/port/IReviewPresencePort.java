package com.betterschool.domain.moderation.adapter.port;

import java.util.Map;

/**
 * 审核端 presence 端口（spec §5.3）：审核长连的在线集合，落 Redis——
 * 可跨实例共享、可查在线人数、重启可恢复（在线集合不是锁，无互斥语义）。
 * 写点三处：连接建立写入、心跳（任意入站帧）刷新、断开删除；
 * lastSeenMs 供读方按心跳口径过滤陈旧条目（如实例崩溃后无断开回调的残留）
 */
public interface IReviewPresencePort {

    /** 记录/刷新在线：连接建立与心跳入站共用（值 = 最近活跃时刻毫秒） */
    void touch(String userId, long lastSeenAtMs);

    /** 断开删除：正常断开与判死清理（经 close 事件）都会走到 */
    void markOffline(String userId);

    /** 在线快照：userId → 最近活跃时刻毫秒（读方自行按新鲜度过滤） */
    Map<String, Long> onlineSnapshot();

    /**
     * 在线审核员数（票 11 可观测口径 ①，spec §9）：活跃时刻在 freshnessMs 内的条目数——
     * 心跳 25s 一次、60s 判死（CONTEXT.md「心跳」口径），超阈值的条目是实例崩溃残留（无断开回调），不计入在线
     */
    int countOnline(long freshnessMs);
}
