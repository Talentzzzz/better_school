package com.betterschool.infrastructure.adapter.repository.chat;

import com.betterschool.domain.chat.adapter.repository.IChatRepository;
import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.entity.RoomEntity;
import com.betterschool.domain.chat.model.entity.RoomMemberEntity;
import com.betterschool.domain.chat.model.entity.TimelineEntity;
import com.betterschool.domain.chat.model.valobj.CardRefTypeVO;
import com.betterschool.domain.chat.model.valobj.DirectRoomPairVO;
import com.betterschool.domain.chat.model.valobj.ChatMessagePushMessage;
import com.betterschool.domain.chat.model.valobj.ChatReadPushMessage;
import com.betterschool.domain.chat.model.valobj.ChatRecallPushMessage;
import com.betterschool.domain.chat.model.valobj.MsgStatusVO;
import com.betterschool.domain.chat.model.valobj.MsgTypeVO;
import com.betterschool.domain.chat.model.valobj.RecallByVO;
import com.betterschool.domain.chat.model.valobj.RoomSourceVO;
import com.betterschool.domain.chat.model.valobj.RoomListItemVO;
import com.betterschool.domain.chat.model.valobj.TimelineItemVO;
import com.betterschool.infrastructure.dao.chat.IChatDao;
import com.betterschool.infrastructure.dao.chat.po.DirectRoomPairPO;
import com.betterschool.infrastructure.dao.chat.po.MessagePO;
import com.betterschool.infrastructure.dao.chat.po.RoomPO;
import com.betterschool.infrastructure.dao.chat.po.RoomListItemPO;
import com.betterschool.infrastructure.dao.chat.po.RoomMemberPO;
import com.betterschool.infrastructure.dao.chat.po.TimelinePO;
import com.betterschool.infrastructure.outbox.OutboxMessageStore;
import com.betterschool.types.common.Constants;
import com.betterschool.types.id.IIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ChatRepository implements IChatRepository {

    private final IChatDao chatDao;
    private final OutboxMessageStore outboxMessageStore;
    private final TransactionTemplate transactionTemplate;
    private final IIdGenerator idGenerator;

    // ===== 原子用例（仓储内事务，PROPAGATION_REQUIRED：与调用方既有事务合流） =====

    @Override
    public RoomEntity createDirectRoom(RoomEntity room, String targetUserId) {
        return transactionTemplate.execute(status -> {
            insertRoom(room);
            RoomMemberEntity self = new RoomMemberEntity();
            self.setRoomId(room.getRoomId());
            self.setUserId(room.getInitiatorId());
            RoomMemberEntity target = new RoomMemberEntity();
            target.setRoomId(room.getRoomId());
            target.setUserId(targetUserId);
            insertRoomMembers(List.of(self, target));
            // 配对行收尾闸门：撞 uk_pair（同一对人恒一行）即整事务回滚，
            // 服务层捕获 DuplicateKeyException 后经 pair 表幂等回读既有会话
            LocalDateTime now = LocalDateTime.now();
            DirectRoomPairPO pair = new DirectRoomPairPO();
            // user_id 是 UUID 字符串，配对键按字符串字典序取规范序（计算收敛在 DirectRoomPairVO，与查重同口径）
            DirectRoomPairVO pairKey = DirectRoomPairVO.canonicalOf(room.getInitiatorId(), targetUserId);
            pair.setUserMin(pairKey.getUserMin());
            pair.setUserMax(pairKey.getUserMax());
            pair.setRoomId(room.getRoomId());
            pair.setCreateTime(now);
            pair.setUpdateTime(now);
            chatDao.insertDirectRoomPair(pair);
            return room;
        });
    }

    @Override
    public MessageEntity deliverMessage(MessageEntity message, String preview, ChatMessagePushMessage pushMessage) {
        return transactionTemplate.execute(status -> {
            long seq = increaseLastSeq(message.getRoomId());
            message.setSeq(seq);
            insertMessage(message);

            // 扇出：在会话成员各一行 timeline，per-user delivery_seq 取号（被移除成员停止扇出）
            List<RoomMemberEntity> members = listRoomMembers(message.getRoomId());
            List<TimelineEntity> timelines = new ArrayList<>(members.size());
            for (RoomMemberEntity member : members) {
                TimelineEntity timeline = new TimelineEntity();
                timeline.setUserId(member.getUserId());
                timeline.setRoomId(message.getRoomId());
                timeline.setSeq(seq);
                timeline.setMsgId(message.getMessageId());
                timeline.setDeliverySeq(increaseUserSeq(member.getUserId(), 1));
                timelines.add(timeline);
            }
            insertTimelines(timelines);
            // 新消息清空成员隐藏位：删除的会话弹回列表顶部（微信行为）
            clearHidden(message.getRoomId());

            // 列表副本落用户维度成员行（本票过渡：与取号同事务；票 02 起归扩散消费端单一写入方）
            updateMemberCopies(message.getRoomId(), seq, message.getMsgType(), preview, message.getCreatedAt());

            // 推送载荷的事务内生成字段（序号/主键/时间）在这里补齐后与业务同事务落 outbox
            pushMessage.setSeq(seq);
            pushMessage.setMessageId(message.getMessageId());
            pushMessage.setCreatedAt(message.getCreatedAt());
            savePushMessage(pushMessage);
            return message;
        });
    }

    @Override
    public void advanceReadWatermark(String roomId, String userId, long readSeq) {
        transactionTemplate.executeWithoutResult(status -> {
            boolean advanced = updateLastReadSeq(roomId, userId, readSeq);
            if (advanced) {
                saveReadMessage(ChatReadPushMessage.builder()
                        .roomId(roomId)
                        .userId(userId)
                        .readSeq(readSeq)
                        .build());
            }
        });
    }

    @Override
    public void recallMessageWithEvent(String roomId, Long seq, LocalDateTime recalledAt, RecallByVO recalledBy,
                                       String preview) {
        transactionTemplate.executeWithoutResult(status -> {
            // 条件 UPDATE（status=NORMAL 才生效）：并发撤回先到先得，落空即已被撤回，幂等静默
            boolean recalled = recallMessage(roomId, seq, recalledAt, recalledBy);
            if (!recalled) {
                return;
            }
            // 预览替换带 last_seq 守卫：该消息已不是最后一条时落空
            markLastMsgRecalled(roomId, seq, preview);
            saveRecallMessage(ChatRecallPushMessage.builder()
                    .roomId(roomId)
                    .seq(seq)
                    .build());
        });
    }

    // ===== room =====

    @Override
    public void insertRoom(RoomEntity room) {
        LocalDateTime now = LocalDateTime.now();
        // 业务 ID 应用层 insert 前发号、随行一次写入（物理自增 id 只是内部键，永不回写业务键）
        room.setRoomId(idGenerator.nextId());
        RoomPO roomPO = new RoomPO();
        roomPO.setRoomId(room.getRoomId());
        roomPO.setSource(room.getSource() == null ? null : room.getSource().name());
        roomPO.setInitiatorId(room.getInitiatorId());
        roomPO.setLastSeq(0L);
        roomPO.setCreateTime(now);
        roomPO.setUpdateTime(now);
        chatDao.insertRoom(roomPO);
        room.setLastSeq(0L);
        room.setCreatedAt(now);
    }

    @Override
    public RoomEntity findRoomById(String roomId) {
        return toRoomEntity(chatDao.selectRoomById(roomId));
    }

    @Override
    public RoomEntity findDirectRoom(String userMin, String userMax) {
        String roomId = chatDao.selectDirectRoomId(userMin, userMax);
        return roomId == null ? null : findRoomById(roomId);
    }

    @Override
    public long increaseLastSeq(String roomId) {
        chatDao.increaseLastSeq(roomId);
        Long lastSeq = chatDao.selectLastSeq(roomId);
        if (lastSeq == null) {
            throw new IllegalStateException("会话不存在，无法取号：" + roomId);
        }
        return lastSeq;
    }

    @Override
    public void updateMemberCopies(String roomId, long seq, MsgTypeVO msgType, String preview, LocalDateTime lastMsgAt) {
        chatDao.updateMemberCopies(roomId, seq, msgType.name(), preview, lastMsgAt);
    }

    @Override
    public List<RoomListItemVO> listRoomItems(String userId, int offset, int limit) {
        return chatDao.selectRoomItems(userId, offset, limit).stream()
                .map(this::toRoomListItemVO)
                .toList();
    }

    @Override
    public long countRoomsForUser(String userId) {
        return chatDao.countRoomsForUser(userId);
    }

    // ===== room_member（成员状态，每会话恒两行） =====

    @Override
    public void insertRoomMembers(List<RoomMemberEntity> members) {
        LocalDateTime now = LocalDateTime.now();
        List<RoomMemberPO> memberPOs = members.stream().map(member -> {
            RoomMemberPO memberPO = new RoomMemberPO();
            memberPO.setRoomId(member.getRoomId());
            memberPO.setUserId(member.getUserId());
            memberPO.setCreateTime(now);
            memberPO.setUpdateTime(now);
            return memberPO;
        }).toList();
        chatDao.insertRoomMembers(memberPOs);
        for (int i = 0; i < memberPOs.size(); i++) {
            RoomMemberEntity member = members.get(i);
            member.setId(memberPOs.get(i).getId());
            member.setLastSeqCopy(0L);
            member.setLastReadSeq(0L);
            member.setCreatedAt(now);
        }
    }

    @Override
    public RoomMemberEntity findRoomMember(String roomId, String userId) {
        return toRoomMemberEntity(chatDao.selectRoomMember(roomId, userId));
    }

    @Override
    public List<RoomMemberEntity> listRoomMembers(String roomId) {
        return chatDao.listRoomMembers(roomId).stream().map(this::toRoomMemberEntity).toList();
    }

    @Override
    public long countRoomMembers(String roomId) {
        return chatDao.countRoomMembers(roomId);
    }

    @Override
    public boolean updateLastReadSeq(String roomId, String userId, long lastReadSeq) {
        return chatDao.updateLastReadSeq(roomId, userId, lastReadSeq) > 0;
    }

    @Override
    public void clearHidden(String roomId) {
        chatDao.clearHidden(roomId);
    }

    @Override
    public void hideRoom(String roomId, String userId, LocalDateTime hiddenAt) {
        chatDao.updateHiddenAt(roomId, userId, hiddenAt);
    }

    // ===== message =====

    @Override
    public void insertMessage(MessageEntity message) {
        LocalDateTime now = LocalDateTime.now();
        // 业务 ID 应用层 insert 前发号、随行一次写入（timeline.msg_id 外联的就是这个业务键）
        message.setMessageId(idGenerator.nextId());
        MessagePO messagePO = new MessagePO();
        messagePO.setMsgId(message.getMessageId());
        messagePO.setRoomId(message.getRoomId());
        messagePO.setSeq(message.getSeq());
        messagePO.setFromUserId(message.getFromUserId());
        messagePO.setMsgType(message.getMsgType().name());
        messagePO.setContent(message.getContent());
        messagePO.setClientMsgId(message.getClientMsgId());
        messagePO.setRefType(message.getRefType() == null ? null : message.getRefType().name());
        messagePO.setRefId(message.getRefId());
        messagePO.setState(MsgStatusVO.NORMAL.name());
        messagePO.setCreateTime(now);
        messagePO.setUpdateTime(now);
        chatDao.insertMessage(messagePO);
        message.setStatus(MsgStatusVO.NORMAL);
        message.setCreatedAt(now);
    }

    @Override
    public MessageEntity findMessage(String roomId, Long seq) {
        return toMessageEntity(chatDao.selectMessage(roomId, seq));
    }

    @Override
    public MessageEntity findMessageByClientMsgId(String roomId, String clientMsgId) {
        return toMessageEntity(chatDao.selectMessageByClientMsgId(roomId, clientMsgId));
    }

    @Override
    public boolean recallMessage(String roomId, Long seq, LocalDateTime recalledAt, RecallByVO recalledBy) {
        return chatDao.recallMessage(roomId, seq, recalledAt, recalledBy.name()) > 0;
    }

    @Override
    public MessageEntity findMessageById(String messageId) {
        return toMessageEntity(chatDao.selectMessageById(messageId));
    }

    @Override
    public void markLastMsgRecalled(String roomId, long seq, String preview) {
        chatDao.markLastMsgRecalled(roomId, seq, preview);
    }

    @Override
    public List<MessageEntity> listMessagesBefore(String roomId, Long beforeSeq, int limit) {
        return chatDao.selectMessagesBefore(roomId, beforeSeq, limit).stream()
                .map(this::toMessageEntity)
                .toList();
    }

    // ===== timeline =====

    @Override
    public void insertTimelines(List<TimelineEntity> timelines) {
        if (timelines == null || timelines.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<TimelinePO> timelinePOs = timelines.stream().map(timeline -> {
            TimelinePO timelinePO = new TimelinePO();
            timelinePO.setUserId(timeline.getUserId());
            timelinePO.setRoomId(timeline.getRoomId());
            timelinePO.setSeq(timeline.getSeq());
            timelinePO.setMsgId(timeline.getMsgId());
            timelinePO.setDeliverySeq(timeline.getDeliverySeq());
            timelinePO.setCreateTime(now);
            timelinePO.setUpdateTime(now);
            return timelinePO;
        }).toList();
        chatDao.insertTimelines(timelinePOs);
    }

    @Override
    public List<TimelineItemVO> listTimelineAfter(String userId, long afterDeliverySeq, int limit) {
        return chatDao.selectTimelineAfter(userId, afterDeliverySeq, limit).stream()
                .map(po -> new TimelineItemVO(toMessageEntity(po), po.getDeliverySeq()))
                .toList();
    }

    // ===== user_seq =====

    @Override
    public long increaseUserSeq(String userId, int n) {
        chatDao.upsertIncreaseUserSeq(userId, n);
        Long seq = chatDao.selectUserSeq(userId);
        if (seq == null) {
            throw new IllegalStateException("投递序号计数器取号失败：" + userId);
        }
        return seq;
    }

    // ===== outbox =====

    @Override
    public void savePushMessage(ChatMessagePushMessage message) {
        outboxMessageStore.save(Constants.CHAT_PUSH_TOPIC, message);
    }

    @Override
    public void saveReadMessage(ChatReadPushMessage message) {
        outboxMessageStore.save(Constants.CHAT_READ_TOPIC, message);
    }

    @Override
    public void saveRecallMessage(ChatRecallPushMessage message) {
        outboxMessageStore.save(Constants.CHAT_RECALL_TOPIC, message);
    }

    // ===== PO ↔ Entity =====

    private RoomListItemVO toRoomListItemVO(RoomListItemPO po) {
        RoomListItemVO vo = new RoomListItemVO();
        vo.setRoomId(po.getRoomId());
        vo.setPeerUserId(po.getPeerUserId());
        vo.setSource(po.getSource() == null ? null : RoomSourceVO.valueOf(po.getSource()));
        vo.setInitiatorId(po.getInitiatorId());
        vo.setLastMsgType(po.getLastMsgType() == null ? null : MsgTypeVO.valueOf(po.getLastMsgType()));
        vo.setLastMsgPreview(po.getLastMsgPreview());
        vo.setLastMsgAt(po.getLastMsgAt());
        vo.setUnread(po.getLastSeqCopy() - po.getLastReadSeq());
        return vo;
    }

    private RoomEntity toRoomEntity(RoomPO roomPO) {
        if (roomPO == null) {
            return null;
        }
        RoomEntity room = new RoomEntity();
        room.setRoomId(roomPO.getRoomId());
        room.setSource(roomPO.getSource() == null ? null : RoomSourceVO.valueOf(roomPO.getSource()));
        room.setInitiatorId(roomPO.getInitiatorId());
        room.setLastSeq(roomPO.getLastSeq());
        room.setCreatedAt(roomPO.getCreateTime());
        return room;
    }

    private RoomMemberEntity toRoomMemberEntity(RoomMemberPO roomMemberPO) {
        if (roomMemberPO == null) {
            return null;
        }
        RoomMemberEntity roomMember = new RoomMemberEntity();
        roomMember.setId(roomMemberPO.getId());
        roomMember.setRoomId(roomMemberPO.getRoomId());
        roomMember.setUserId(roomMemberPO.getUserId());
        roomMember.setLastSeqCopy(roomMemberPO.getLastSeqCopy());
        roomMember.setLastReadSeq(roomMemberPO.getLastReadSeq());
        roomMember.setHiddenAt(roomMemberPO.getHiddenAt());
        roomMember.setCreatedAt(roomMemberPO.getCreateTime());
        return roomMember;
    }

    private MessageEntity toMessageEntity(MessagePO messagePO) {
        if (messagePO == null) {
            return null;
        }
        MessageEntity message = new MessageEntity();
        message.setMessageId(messagePO.getMsgId());
        message.setRoomId(messagePO.getRoomId());
        message.setSeq(messagePO.getSeq());
        message.setFromUserId(messagePO.getFromUserId());
        message.setMsgType(MsgTypeVO.valueOf(messagePO.getMsgType()));
        message.setContent(messagePO.getContent());
        message.setClientMsgId(messagePO.getClientMsgId());
        message.setRefType(messagePO.getRefType() == null ? null : CardRefTypeVO.valueOf(messagePO.getRefType()));
        message.setRefId(messagePO.getRefId());
        message.setStatus(MsgStatusVO.valueOf(messagePO.getState()));
        message.setRecalledAt(messagePO.getRecalledAt());
        message.setRecalledBy(messagePO.getRecalledBy() == null ? null : RecallByVO.valueOf(messagePO.getRecalledBy()));
        message.setCreatedAt(messagePO.getCreateTime());
        return message;
    }
}
