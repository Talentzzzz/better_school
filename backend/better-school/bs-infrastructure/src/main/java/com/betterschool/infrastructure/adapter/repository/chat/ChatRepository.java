package com.betterschool.infrastructure.adapter.repository.chat;

import com.betterschool.domain.chat.adapter.repository.IChatRepository;
import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.entity.RoomEntity;
import com.betterschool.domain.chat.model.entity.RoomMemberEntity;
import com.betterschool.domain.chat.model.entity.TimelineEntity;
import com.betterschool.domain.chat.model.entity.UserRoomEntity;
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
import com.betterschool.infrastructure.dao.chat.po.UserPairRoomPO;
import com.betterschool.infrastructure.dao.chat.po.MessagePO;
import com.betterschool.infrastructure.dao.chat.po.RoomPO;
import com.betterschool.infrastructure.dao.chat.po.RoomListItemPO;
import com.betterschool.infrastructure.dao.chat.po.RoomMemberPO;
import com.betterschool.infrastructure.dao.chat.po.RoomPeerRowPO;
import com.betterschool.infrastructure.dao.chat.po.TimelineDeliveryRowPO;
import com.betterschool.infrastructure.dao.chat.po.TimelinePO;
import com.betterschool.infrastructure.dao.chat.po.UserRoomPO;
import com.betterschool.infrastructure.outbox.OutboxMessageStore;
import com.betterschool.types.common.Constants;
import com.betterschool.types.id.IIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * chat 仓储实现。双维度拆分口径：「这个会话有谁」一律走 room_member 名册（成员扇出/对家回查/
 * 逐用户写路径的成员集来源），user_room 只装 per-user 状态、只做 user_id 前缀查询
 */
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
            LocalDateTime now = LocalDateTime.now();
            // 名册与用户状态成对写入：1v1 恒两行名册（join_seq=0 入群水位）+ 两行 user_room（read_seq=0）
            RoomMemberEntity selfMember = new RoomMemberEntity();
            selfMember.setRoomId(room.getRoomId());
            selfMember.setUserId(room.getInitiatorId());
            RoomMemberEntity targetMember = new RoomMemberEntity();
            targetMember.setRoomId(room.getRoomId());
            targetMember.setUserId(targetUserId);
            insertRoomMembers(List.of(selfMember, targetMember));
            UserRoomEntity self = new UserRoomEntity();
            self.setRoomId(room.getRoomId());
            self.setUserId(room.getInitiatorId());
            UserRoomEntity target = new UserRoomEntity();
            target.setRoomId(room.getRoomId());
            target.setUserId(targetUserId);
            insertUserRooms(List.of(self, target));
            // 配对行收尾闸门：撞 uk_pair（同一对人恒一行）即整事务回滚，
            // 服务层捕获 DuplicateKeyException 后经 pair 表幂等回读既有会话
            UserPairRoomPO pair = new UserPairRoomPO();
            // user_id 是 UUID 字符串，配对键按字符串字典序取规范序（计算收敛在 DirectRoomPairVO，与查重同口径）
            DirectRoomPairVO pairKey = DirectRoomPairVO.canonicalOf(room.getInitiatorId(), targetUserId);
            pair.setUserMin(pairKey.getUserMin());
            pair.setUserMax(pairKey.getUserMax());
            pair.setRoomId(room.getRoomId());
            pair.setCreateTime(now);
            pair.setUpdateTime(now);
            chatDao.insertUserPairRoom(pair);
            return room;
        });
    }

    @Override
    public MessageEntity deliverMessage(MessageEntity message, String preview, ChatMessagePushMessage pushMessage) {
        return transactionTemplate.execute(status -> {
            long seq = increaseLastSeq(message.getRoomId());
            message.setSeq(seq);
            insertMessage(message);

            // 扇出：按名册逐成员一行 timeline，per-user delivery_seq 取号（被移除成员停止扇出）
            List<String> memberIds = chatDao.listRoomMembers(message.getRoomId()).stream()
                    .map(RoomMemberPO::getUserId).toList();
            List<TimelineEntity> timelines = new ArrayList<>(memberIds.size());
            for (String memberId : memberIds) {
                TimelineEntity timeline = new TimelineEntity();
                timeline.setUserId(memberId);
                timeline.setRoomId(message.getRoomId());
                timeline.setSeq(seq);
                timeline.setMsgId(message.getMessageId());
                timeline.setDeliverySeq(increaseUserSeq(memberId, 1));
                timelines.add(timeline);
            }
            insertTimelines(timelines);
            // 新消息清空成员隐藏位：删除的会话弹回列表顶部（微信行为；名册给定成员集，逐用户执行）
            clearHidden(message.getRoomId());

            // 列表预览落用户维度 user_room 行（本票过渡：与取号同事务；票 02 起归扩散消费端单一写入方）
            updateUserRoomPreview(message.getRoomId(), message.getMsgType(), preview, message.getCreatedAt());

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
            boolean advanced = updateUserReadSeq(roomId, userId, readSeq);
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
        String roomId = chatDao.selectUserPairRoomId(userMin, userMax);
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
    public void updateUserRoomPreview(String roomId, MsgTypeVO msgType, String preview, LocalDateTime lastMsgTime) {
        for (String memberId : rosterMemberIds(roomId)) {
            chatDao.updateUserRoomPreview(roomId, memberId, msgType.name(), preview, lastMsgTime);
        }
    }

    @Override
    public List<RoomListItemVO> listRoomItems(String userId, int offset, int limit) {
        // 会话列表三段查（SQL 层零 JOIN）：①本人 user_room 行分页 → ②room 行批量回查 → ③名册回查对家，
        // Java 按会话拼装。room 行或对家行缺失 = 原 INNER JOIN 语义，该会话不出现
        List<RoomListItemPO> rows = chatDao.selectUserRoomPage(userId, offset, limit);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> roomIds = rows.stream().map(RoomListItemPO::getRoomId).toList();
        Map<String, RoomPO> roomsById = chatDao.selectRoomsByIds(roomIds).stream()
                .collect(Collectors.toMap(RoomPO::getRoomId, Function.identity(), (a, b) -> a));
        Map<String, String> peerByRoom = chatDao.selectPeerMembers(roomIds, userId).stream()
                .collect(Collectors.toMap(RoomPeerRowPO::getRoomId, RoomPeerRowPO::getUserId, (a, b) -> a));
        List<RoomListItemVO> items = new ArrayList<>(rows.size());
        for (RoomListItemPO row : rows) {
            RoomPO room = roomsById.get(row.getRoomId());
            String peerUserId = peerByRoom.get(row.getRoomId());
            if (room == null || peerUserId == null) {
                continue;
            }
            row.setSource(room.getSource());
            row.setInitiatorId(room.getInitiatorId());
            row.setLastSeq(room.getLastSeq());
            row.setPeerUserId(peerUserId);
            items.add(toRoomListItemVO(row));
        }
        return items;
    }

    @Override
    public long countRoomsForUser(String userId) {
        return chatDao.countRoomsForUser(userId);
    }

    // ===== room_member（会话维度名册：「这个会话有谁」） =====

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
        for (int i = 0; i < members.size(); i++) {
            members.get(i).setJoinSeq(0L);
            members.get(i).setCreatedAt(now);
        }
    }

    @Override
    public List<RoomMemberEntity> listRoomMembers(String roomId) {
        return chatDao.listRoomMembers(roomId).stream().map(this::toRoomMemberEntity).toList();
    }

    @Override
    public long countRoomMembers(String roomId) {
        return chatDao.countRoomMembers(roomId);
    }

    // ===== user_room（用户维度会话状态） =====

    @Override
    public void insertUserRooms(List<UserRoomEntity> userRooms) {
        LocalDateTime now = LocalDateTime.now();
        List<UserRoomPO> userRoomPOs = userRooms.stream().map(userRoom -> {
            UserRoomPO userRoomPO = new UserRoomPO();
            userRoomPO.setRoomId(userRoom.getRoomId());
            userRoomPO.setUserId(userRoom.getUserId());
            userRoomPO.setCreateTime(now);
            userRoomPO.setUpdateTime(now);
            return userRoomPO;
        }).toList();
        chatDao.insertUserRooms(userRoomPOs);
        for (UserRoomEntity userRoom : userRooms) {
            userRoom.setReadSeq(0L);
            userRoom.setCreatedAt(now);
        }
    }

    @Override
    public UserRoomEntity findUserRoom(String roomId, String userId) {
        return toUserRoomEntity(chatDao.selectUserRoom(roomId, userId));
    }

    @Override
    public List<UserRoomEntity> listUserRooms(String roomId, List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return chatDao.selectUserRooms(roomId, userIds).stream().map(this::toUserRoomEntity).toList();
    }

    @Override
    public boolean updateUserReadSeq(String roomId, String userId, long readSeq) {
        return chatDao.updateUserRoomReadSeq(roomId, userId, readSeq) > 0;
    }

    @Override
    public void clearHidden(String roomId) {
        for (String memberId : rosterMemberIds(roomId)) {
            chatDao.clearUserRoomHidden(roomId, memberId);
        }
    }

    @Override
    public void hideRoom(String roomId, String userId, LocalDateTime hiddenAt) {
        chatDao.updateUserRoomHiddenAt(roomId, userId, hiddenAt);
    }

    /** 名册成员集：会话级批量写的驱动来源（「这个会话有谁」只从名册取） */
    private List<String> rosterMemberIds(String roomId) {
        return chatDao.listRoomMembers(roomId).stream().map(RoomMemberPO::getUserId).toList();
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
        for (String memberId : rosterMemberIds(roomId)) {
            chatDao.markUserRoomLastMsgRecalled(roomId, memberId, seq, preview);
        }
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
        // 增量补拉两段查（SQL 层零 JOIN）：timeline 投递行扫描 → msg_id 集批量回查消息全量，
        // 按投递序拼装。消息行缺失 = 原 INNER JOIN 语义（timeline 行在而消息行缺失属漂移），跳过
        List<TimelineDeliveryRowPO> rows = chatDao.selectTimelineRows(userId, afterDeliverySeq, limit);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, MessagePO> messagesById = chatDao.selectMessagesByIds(
                rows.stream().map(TimelineDeliveryRowPO::getMsgId).toList()).stream()
                .collect(Collectors.toMap(MessagePO::getMsgId, Function.identity(), (a, b) -> a));
        List<TimelineItemVO> items = new ArrayList<>(rows.size());
        for (TimelineDeliveryRowPO row : rows) {
            MessagePO message = messagesById.get(row.getMsgId());
            if (message == null) {
                continue;
            }
            items.add(new TimelineItemVO(toMessageEntity(message), row.getDeliverySeq()));
        }
        return items;
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
        vo.setLastMsgTime(po.getLastMsgTime());
        vo.setUnread(po.getLastSeq() - po.getReadSeq());
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

    private RoomMemberEntity toRoomMemberEntity(RoomMemberPO memberPO) {
        if (memberPO == null) {
            return null;
        }
        RoomMemberEntity member = new RoomMemberEntity();
        member.setId(memberPO.getId());
        member.setRoomId(memberPO.getRoomId());
        member.setUserId(memberPO.getUserId());
        member.setJoinSeq(memberPO.getJoinSeq());
        member.setCreatedAt(memberPO.getCreateTime());
        return member;
    }

    private UserRoomEntity toUserRoomEntity(UserRoomPO userRoomPO) {
        if (userRoomPO == null) {
            return null;
        }
        UserRoomEntity userRoom = new UserRoomEntity();
        userRoom.setId(userRoomPO.getId());
        userRoom.setRoomId(userRoomPO.getRoomId());
        userRoom.setUserId(userRoomPO.getUserId());
        userRoom.setReadSeq(userRoomPO.getReadSeq());
        userRoom.setHiddenAt(userRoomPO.getHiddenAt());
        userRoom.setCreatedAt(userRoomPO.getCreateTime());
        return userRoom;
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
