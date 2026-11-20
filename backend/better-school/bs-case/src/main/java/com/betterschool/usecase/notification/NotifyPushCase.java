package com.betterschool.usecase.notification;

import com.betterschool.domain.chat.adapter.port.IWsPushPort;
import com.betterschool.domain.chat.model.valobj.ChatWsFrame;
import com.betterschool.domain.chat.model.valobj.ChatWsFrameType;
import com.betterschool.domain.notification.model.valobj.NotifyPushMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 通知 WS 推送用例（BANNER 通道的落地侧决策）：点赞/评论等瞬态横幅组 NOTIF 帧
 * 投递给单收件人（scope/seq/type/title/content/refType/refId payload，与前端渲染约定）。
 * refType/refId 消费侧 null→"" 归一化（老事件回放载荷可能缺列，前端按空串渲染）。
 * 推送是加速路径：不做事务、不做重试，不在线/失败由 IWsPushPort 静默放弃——
 * 漏推由前端按 (scope, seq) 水位补拉重放兜底（保证弹过：上线后把积压的通知逐条弹完）
 */
@Service
@RequiredArgsConstructor
public class NotifyPushCase {

    private final IWsPushPort wsPushPort;

    public void push(NotifyPushMessage message) {
        wsPushPort.deliver(message.getUserId(), ChatWsFrame.builder()
                .type(ChatWsFrameType.NOTIF)
                .payload(Map.of(
                        "scope", message.getScope(),
                        "seq", message.getSeq(),
                        "type", message.getType(),
                        "title", message.getTitle(),
                        "content", message.getContent(),
                        "refType", message.getRefType() == null ? "" : message.getRefType(),
                        "refId", message.getRefId() == null ? "" : message.getRefId()))
                .ts(System.currentTimeMillis())
                .build());
    }
}
