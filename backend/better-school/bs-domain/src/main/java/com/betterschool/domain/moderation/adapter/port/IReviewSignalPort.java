package com.betterschool.domain.moderation.adapter.port;

/**
 * 审核派单信号推送端口（票 11，spec §5.3「推送通道」）：新工单入池事务提交后向**在线审核员**
 * 广播粗粒度总信号——「有新工单」+ 当前积压数，**不按内容类型推**：推送只负责唤醒，不指定给谁、
 * 不锁任何东西；「只擅审某类」的偏好在拉取侧表达。
 * 实现落 trigger 层（review WS 连接注册表广播）；离线审核员不推、不丢单——池是 DB 持久化的，
 * 无人在线就静默积压，上线时由连接建立路径补推当前积压数
 */
public interface IReviewSignalPort {

    /**
     * 广播派单信号给全部在线审核员（帧型 REVIEW_SIGNAL，载荷含当前积压总数）。
     * 调用方保证已处于入池事务提交后；实现内部自取积压数、推送失败按漏推吞掉（客户端兜底收敛）
     */
    void pushNewTaskSignal();
}
