package com.betterschool.domain.chat.service.recall;

import com.betterschool.domain.chat.adapter.repository.IChatRepository;
import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.valobj.RecallByVO;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 撤回实现：窗口/本人校验在事务外快败；撤回 UPDATE + 预览替换 + RECALL 事件的原子性
 * 由 IChatRepository.recallMessageWithEvent 自带事务保证；
 * 已撤回幂等静默（重复点击 / 强撤撞自行撤回），不重复投递 RECALL 事件
 */
@Service
@RequiredArgsConstructor
public class ChatRecallServiceImpl implements IChatRecallService {

    /** 发送者撤回窗口（spec §1 常量） */
    public static final Duration RECALL_WINDOW = Duration.ofMinutes(2);

    /** 会话列表预览替换文案（spec §1 预览规则：已撤回→[已撤回]） */
    public static final String PREVIEW_RECALLED = "[已撤回]";

    private final IChatRepository chatRepository;

    @Override
    public void recall(String roomId, Long seq, String userId) {
        MessageEntity message = chatRepository.findMessage(roomId, seq);
        if (message == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "消息不存在");
        }
        if (message.getFromUserId() == null || !message.getFromUserId().equals(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "仅发送者本人可撤回");
        }
        if (message.isRecalled()) {
            return;
        }
        if (LocalDateTime.now().isAfter(message.getCreatedAt().plus(RECALL_WINDOW))) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "已超过可撤回时间");
        }
        doRecall(message, RecallByVO.USER);
    }

    @Override
    public void forceRecallByAuditor(String messageId) {
        MessageEntity message = chatRepository.findMessageById(messageId);
        if (message == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "消息不存在");
        }
        if (message.isRecalled()) {
            // 已自行撤回：只落举报处理结论，不覆盖撤回者、不重复推送
            return;
        }
        doRecall(message, RecallByVO.AUDITOR);
    }

    private void doRecall(MessageEntity message, RecallByVO recalledBy) {
        // 条件 UPDATE（status=NORMAL 才生效）：并发撤回先到先得，落空即已被撤回，幂等静默
        chatRepository.recallMessageWithEvent(message.getRoomId(), message.getSeq(), LocalDateTime.now(),
                recalledBy, PREVIEW_RECALLED);
    }
}
