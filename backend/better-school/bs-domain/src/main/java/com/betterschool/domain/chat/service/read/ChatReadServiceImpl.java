package com.betterschool.domain.chat.service.read;

import com.betterschool.domain.chat.adapter.repository.IChatRepository;
import com.betterschool.domain.chat.model.entity.RoomEntity;
import com.betterschool.domain.chat.model.entity.UserRoomEntity;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 水位线推进：目标 = 会话水位真源 room.last_seq（打开即已读，未读算式同源），守卫 UPDATE 只前进；
 * 仅水位线实际前进才投递 READ 事件——并发/重复打开时守卫 UPDATE 落空，幂等无噪音。
 * 「水位线 + READ 事件」的原子性由 IChatRepository.advanceReadWatermark 自带事务保证
 */
@Service
@RequiredArgsConstructor
public class ChatReadServiceImpl implements IChatReadService {

    private final IChatRepository chatRepository;

    @Override
    public long markRead(String roomId, String userId) {
        UserRoomEntity self = chatRepository.findUserRoom(roomId, userId);
        if (self == null) {
            // 成员行不存在 = 会话不存在或非成员，两种对外口径不变（先查 room 保 404 语义）
            if (chatRepository.findRoomById(roomId) == null) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "会话不存在");
            }
            throw new BusinessException(ResponseCode.FORBIDDEN, "非会话成员");
        }
        long readSeq = chatRepository.findRoomById(roomId).getLastSeq();
        if (self.getReadSeq() >= readSeq) {
            // 已追平：连守卫 UPDATE 都省了，无事件无写放大
            return self.getReadSeq();
        }
        chatRepository.advanceReadWatermark(roomId, userId, readSeq);
        return readSeq;
    }

    @Override
    public void hideRoom(String roomId, String userId) {
        if (chatRepository.findRoomById(roomId) == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "会话不存在");
        }
        UserRoomEntity self = chatRepository.findUserRoom(roomId, userId);
        if (self == null) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "非会话成员");
        }
        // hidden_at 由 SQL NOW() 落库（时刻一律数据库时钟；该列只作「非 NULL 即隐藏」判断）
        chatRepository.hideRoom(roomId, userId);
    }
}
