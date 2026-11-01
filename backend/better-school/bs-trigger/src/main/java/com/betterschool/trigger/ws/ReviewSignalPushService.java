package com.betterschool.trigger.ws;

import com.betterschool.domain.moderation.adapter.port.IReviewSignalPort;
import com.betterschool.domain.moderation.service.IReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * 审核派单信号推送服务（票 11，spec §5.3「推送通道」）：实现域端口 {@link IReviewSignalPort}，
 * 把「有新工单」广播到 review 连接注册表里的全部在线审核员。信号是**粗粒度总信号**——
 * 帧型 {@code REVIEW_SIGNAL}、载荷只带当前积压总数（backlog），**不按内容类型推、不指定收件人、
 * 不做分配、不引入任何锁**——公平约束（一次一条 + 先到先审 + 类型分区）全在拉取侧（claim）。
 * <p>
 * 两个触发点：① 入池事务提交后广播（基础设施 afterCommit 钩子，离线者不推、上线由 ② 补）；
 * ② 审核员连接建立时立即补推当前积压数（离线期间不推、不丢单——池是 DB 持久化的，
 * 无人在线静默积压，上线后信号与池中实际一致）。
 * 推送失败不重试（漏推合法，与 chat 补拉同口径——审核端的兜底是上线补推 + 手动刷新计数条）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewSignalPushService implements IReviewSignalPort {

    private final ReviewConnectionRegistry connectionRegistry;
    private final ReviewWsPusher pusher;
    private final IReviewService reviewService;

    @Override
    public void pushNewTaskSignal() {
        Map<String, WebSocketSession> online = connectionRegistry.snapshot();
        if (online.isEmpty()) {
            // 无人在线：静默积压（池持久化，不推不丢单），连积压数都不必查
            return;
        }
        broadcast(online);
        log.info("[审核派单] 新工单入池，已向 {} 名在线审核员广播派单信号", online.size());
    }

    /** 审核员连接建立时立即补推当前积压数（离线转在线的「上线补推」） */
    public void pushBacklogTo(WebSocketSession session) {
        pusher.push(session, signalFrame());
    }

    private void broadcast(Map<String, WebSocketSession> online) {
        ReviewWsFrame frame = signalFrame();
        online.values().forEach(session -> pusher.push(session, frame));
    }

    /** 派单信号帧：粗粒度总信号，载荷只带当前积压总数（不按类型分摊，类型计数走 GET /tasks/counts） */
    private ReviewWsFrame signalFrame() {
        return ReviewWsFrame.builder()
                .type(ReviewWsFrameType.REVIEW_SIGNAL)
                .payload(Map.of("backlog", backlogTotal()))
                .ts(System.currentTimeMillis())
                .build();
    }

    /** 当前积压总数（工单池 PENDING 行数求和，服务层统一口径；推前现算，保证信号与池中实际一致） */
    private long backlogTotal() {
        return reviewService.backlogTotal();
    }
}
