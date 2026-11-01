package com.betterschool.domain.chat.service.read;

import com.betterschool.domain.chat.adapter.repository.IChatRepository;
import com.betterschool.domain.chat.model.entity.RoomEntity;
import com.betterschool.domain.chat.model.entity.RoomMemberEntity;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 水位线推进：目标 = 本人在成员行上的水位副本 last_seq_copy（打开即已读，未读算式同源就地处），守卫 UPDATE 只前进；
 * 仅水位线实际前进才投递 READ 事件——并发/重复打开时守卫 UPDATE 落空，幂等无噪音。
 * 「水位线 + READ 事件」的原子性由 IChatRepository.advanceReadWatermark 自带事务保证
 */
@Service
@RequiredArgsConstructor
public class ChatReadServiceImpl implements IChatReadService {

    private final IChatRepository chatRepository;

    @Override
    public long markRead(String roomId, String userId) {
        RoomMemberEntity self = chatRepository.findRoomMember(roomId, userId);
        if (self == null) {
            // 成员行不存在 = 会话不存在或非成员，两种对外口径不变（先查 room 保 404 语义）
            if (chatRepository.findRoomById(roomId) == null) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "会话不存在");
            }
            throw new BusinessException(ResponseCode.FORBIDDEN, "非会话成员");
        }
        long readSeq = self.getLastSeqCopy();
        if (self.getLastReadSeq() >= readSeq) {
            // 已追平：连守卫 UPDATE 都省了，无事件无写放大
            return self.getLastReadSeq();
        }
        chatRepository.advanceReadWatermark(roomId, userId, readSeq);
        return readSeq;
    }

    @Override
    public void hideRoom(String roomId, String userId) {
        if (chatRepository.findRoomById(roomId) == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "会话不存在");
        }
        RoomMemberEntity self = chatRepository.findRoomMember(roomId, userId);
        if (self == null) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "非会话成员");
        }
        chatRepository.hideRoom(roomId, userId, LocalDateTime.now());
    }
}
