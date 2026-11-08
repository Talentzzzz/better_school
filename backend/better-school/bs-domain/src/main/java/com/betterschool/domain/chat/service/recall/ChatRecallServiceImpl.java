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
 * 撤回实现：窗口/本人校验在事务外快败；撤回 UPDATE + RECALL 事件的原子性
 * 由 IChatRepository.recallMessageWithEvent 自带事务保证；预览零维护写
 * （票 02：读时按 message 真相现算，渲染归 RoomPreviewRenderer，撤回即时反映）；
 * 已撤回幂等静默（重复点击 / 强撤撞自行撤回），不重复投递 RECALL 事件
 */
@Service
@RequiredArgsConstructor
public class ChatRecallServiceImpl implements IChatRecallService {

    /** 发送者撤回窗口（spec §1 常量） */
    public static final Duration RECALL_WINDOW = Duration.ofMinutes(2);

    private final IChatRepository chatRepository;

    @Override
    public void recall(String roomId, Long seq, String userId) {
        MessageEntity messageEntity = chatRepository.findMessage(roomId, seq);
        if (messageEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "消息不存在");
        }
        if (messageEntity.getFromUserId() == null || !messageEntity.getFromUserId().equals(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "仅发送者本人可撤回");
        }
        if (messageEntity.isRecalled()) {
            return;
        }
        if (LocalDateTime.now().isAfter(messageEntity.getCreatedAt().plus(RECALL_WINDOW))) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "已超过可撤回时间");
        }
        doRecall(messageEntity, RecallByVO.USER);
    }

    private void doRecall(MessageEntity messageEntity, RecallByVO recalledBy) {
        // 条件 UPDATE（status=NORMAL 才生效）：并发撤回先到先得，落空即已被撤回，幂等静默；
        // recalled_at 由 SQL NOW() 落库（时刻一律数据库时钟）
        chatRepository.recallMessageWithEvent(messageEntity.getRoomId(), messageEntity.getSeq(), recalledBy);
    }
}
