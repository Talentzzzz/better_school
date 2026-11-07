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
import com.betterschool.domain.chat.model.valobj.RoomSourceEnum;
import com.betterschool.domain.chat.model.valobj.RoomListItemVO;
import com.betterschool.domain.chat.model.valobj.TimelineItemVO;
import com.betterschool.infrastructure.dao.chat.IChatDao;
import com.betterschool.infrastructure.dao.chat.po.UserPairRoom;
import com.betterschool.infrastructure.dao.chat.po.Message;
import com.betterschool.infrastructure.dao.chat.po.Room;
import com.betterschool.infrastructure.dao.chat.po.RoomListItem;
import com.betterschool.infrastructure.dao.chat.po.RoomMember;
import com.betterschool.infrastructure.dao.chat.po.RoomPeerRow;
import com.betterschool.infrastructure.dao.chat.po.TimelineDeliveryRow;
import com.betterschool.infrastructure.dao.chat.po.Timeline;
import com.betterschool.infrastructure.dao.chat.po.UserRoom;
import com.betterschool.infrastructure.outbox.OutboxMessageStore;
import com.betterschool.types.common.Constants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    // ===== 原子用例（仓储内事务，PROPAGATION_REQUIRED：与调用方既有事务合流） =====

    @Override
    public RoomEntity createDirectRoom(RoomEntity roomEntity, String targetUserId) {
        // 业务 ID 应用层 insert 前发号：roomId 先于事务生成，全部 PO 事务外构建（时刻一律 SQL NOW()，
        // Java 不造时间），事务内只做写入
        roomEntity.setRoomId(UUID.randomUUID().toString());
        roomEntity.setLastSeq(0L);
        String roomId = roomEntity.getRoomId();

        Room room = new Room();
        room.setRoomId(roomEntity.getRoomId());
        room.setSource(roomEntity.getSource() == null ? null : roomEntity.getSource().name());
        room.setCreatorId(roomEntity.getCreatorId());
        room.setLastSeq(0L);
        // 名册与用户状态成对写入：1v1 恒两行名册（join_seq=0 入群水位）+ 两行 user_room（read_seq=0）
        RoomMember creatorMember = new RoomMember();
        creatorMember.setRoomId(roomId);
        creatorMember.setUserId(roomEntity.getCreatorId());
        RoomMember targetMember = new RoomMember();
        targetMember.setRoomId(roomId);
        targetMember.setUserId(targetUserId);
        List<RoomMember> members = List.of(creatorMember, targetMember);
        UserRoom creatorUserRoom = new UserRoom();
        creatorUserRoom.setRoomId(roomId);
        creatorUserRoom.setUserId(roomEntity.getCreatorId());
        UserRoom targetUserRoom = new UserRoom();
        targetUserRoom.setRoomId(roomId);
        targetUserRoom.setUserId(targetUserId);
        List<UserRoom> userRooms = List.of(creatorUserRoom, targetUserRoom);
        // 配对行收尾闸门：撞 uk_pair（同一对人恒一行）即整事务回滚，
        // 服务层捕获 DuplicateKeyException 后经 pair 表幂等回读既有会话
        // user_id 是 UUID 字符串，配对键按字符串字典序取规范序（计算收敛在 DirectRoomPairVO，与查重同口径）
        DirectRoomPairVO pairKey = DirectRoomPairVO.canonicalOf(roomEntity.getCreatorId(), targetUserId);
        UserPairRoom pair = new UserPairRoom();
        pair.setUserMin(pairKey.getUserMin());
        pair.setUserMax(pairKey.getUserMax());
        pair.setRoomId(roomId);

        transactionTemplate.executeWithoutResult(status -> {
            chatDao.insertRoom(room);
            chatDao.insertRoomMembers(members);
            chatDao.insertUserRooms(userRooms);
            chatDao.insertUserPairRoom(pair);
        });
        return roomEntity;
    }

    @Override
    public MessageEntity deliverMessage(MessageEntity messageEntity, ChatMessagePushMessage pushMessage) {
        return transactionTemplate.execute(status -> {
            long seq = increaseLastSeq(messageEntity.getRoomId());
            messageEntity.setSeq(seq);
            insertMessage(messageEntity);

            // 扇出：按名册逐成员一行 timeline，per-user delivery_seq 取号（被移除成员停止扇出）
            List<String> memberIds = chatDao.listRoomMembers(messageEntity.getRoomId()).stream()
                    .map(RoomMember::getUserId).toList();
            List<TimelineEntity> timelines = new ArrayList<>(memberIds.size());
            for (String memberId : memberIds) {
                TimelineEntity timeline = new TimelineEntity();
                timeline.setUserId(memberId);
                timeline.setRoomId(messageEntity.getRoomId());
                timeline.setSeq(seq);
                timeline.setMsgId(messageEntity.getMessageId());
                timeline.setDeliverySeq(increaseUserSeq(memberId, 1));
                timelines.add(timeline);
            }
            insertTimelines(timelines);
            // 新消息清空成员隐藏位：删除的会话弹回列表顶部（微信行为；名册给定成员集，逐用户执行）
            clearHidden(messageEntity.getRoomId());
            messageEntity.setCreatedAt(toMessageEntity(
                    chatDao.selectMessage(messageEntity.getRoomId(), messageEntity.getSeq())).getCreatedAt());

            // 列表排序键落用户维度 user_room 行（票 02 预览去副本：只刷 last_msg_time，预览读时现算）
            updateUserRoomLastMsgTime(messageEntity.getRoomId(), messageEntity.getCreatedAt());

            // 推送载荷的事务内生成字段（序号/主键/时间）在这里补齐后与业务同事务落 outbox
            pushMessage.setSeq(seq);
            pushMessage.setMessageId(messageEntity.getMessageId());
            pushMessage.setCreatedAt(messageEntity.getCreatedAt());
            savePushMessage(pushMessage);
            return messageEntity;
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
    public void recallMessageWithEvent(String roomId, Long seq, RecallByVO recalledBy) {
        transactionTemplate.executeWithoutResult(status -> {
            // 条件 UPDATE（status=NORMAL 才生效）：并发撤回先到先得，落空即已被撤回，幂等静默
            boolean recalled = recallMessage(roomId, seq, recalledBy);
            if (!recalled) {
                return;
            }
            // 预览零维护写（票 02：读时按 message 真相现算，撤回即时反映）
            saveRecallMessage(ChatRecallPushMessage.builder()
                    .roomId(roomId)
                    .seq(seq)
                    .build());
        });
    }

    // ===== room =====

    @Override
    public void insertRoom(RoomEntity roomEntity) {
        // 业务 ID 应用层 insert 前发号、随行一次写入（物理自增 id 只是内部键，永不回写业务键）
        roomEntity.setRoomId(UUID.randomUUID().toString());
        Room room = new Room();
        room.setRoomId(roomEntity.getRoomId());
        room.setSource(roomEntity.getSource() == null ? null : roomEntity.getSource().name());
        room.setCreatorId(roomEntity.getCreatorId());
        room.setLastSeq(0L);
        chatDao.insertRoom(room);
        roomEntity.setLastSeq(0L);
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
    public void updateUserRoomLastMsgTime(String roomId, LocalDateTime lastMsgTime) {
        for (String memberId : rosterMemberIds(roomId)) {
            chatDao.updateUserRoomLastMsgTime(roomId, memberId, lastMsgTime);
        }
    }

    @Override
    public List<RoomListItemVO> listRoomItems(String userId, int offset, int limit) {
        // 会话列表四段查（SQL 层零 JOIN）：①本人 user_room 行分页 → ②room 行批量回查 → ③名册回查对家
        // → ④按 (room_id, room.last_seq) 逐会话点查 message 最新一条（预览真源，票 02 预览去副本），
        // Java 按会话拼装。room 行或对家行缺失 = 原 INNER JOIN 语义，该会话不出现
        List<RoomListItem> rows = chatDao.selectUserRoomPage(userId, offset, limit);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> roomIds = rows.stream().map(RoomListItem::getRoomId).toList();
        Map<String, Room> roomsById = chatDao.selectRoomsByIds(roomIds).stream()
                .collect(Collectors.toMap(Room::getRoomId, Function.identity(), (a, b) -> a));
        Map<String, String> peerByRoom = chatDao.selectPeerMembers(roomIds, userId).stream()
                .collect(Collectors.toMap(RoomPeerRow::getRoomId, RoomPeerRow::getUserId, (a, b) -> a));
        List<RoomListItemVO> items = new ArrayList<>(rows.size());
        for (RoomListItem row : rows) {
            Room room = roomsById.get(row.getRoomId());
            String peerUserId = peerByRoom.get(row.getRoomId());
            if (room == null || peerUserId == null) {
                continue;
            }
            row.setSource(room.getSource());
            row.setCreatorId(room.getCreatorId());
            row.setLastSeq(room.getLastSeq());
            row.setPeerUserId(peerUserId);
            RoomListItemVO item = toRoomListItemVO(row);
            if (room.getLastSeq() != null && room.getLastSeq() > 0) {
                // 四段查之④ 预览真源：按 (room_id, room.last_seq) 逐会话点查最新一条消息
                //（last_seq=0 尚无消息不查；§9.7 批量回查的例外：锚点是 (room_id, seq) 复合键，
                // msg_id 要点查后才知，无法先集后查——逐会话走 uk_room_seq 点查原语，
                // 上限 = 页大小（controller MAX_PAGE_SIZE=200），非无界 N+1）
                item.setLastMessage(toMessageEntity(chatDao.selectMessage(row.getRoomId(), room.getLastSeq())));
            }
            items.add(item);
        }
        return items;
    }

    @Override
    public long countRoomsForUser(String userId) {
        return chatDao.countRoomsForUser(userId);
    }

    // ===== room_member（会话维度名册：「这个会话有谁」） =====

    @Override
    public void insertRoomMembers(List<RoomMemberEntity> memberEntities) {
        List<RoomMember> members = new ArrayList<>(memberEntities.size());
        for (RoomMemberEntity memberEntity : memberEntities) {
            RoomMember member = new RoomMember();
            member.setRoomId(memberEntity.getRoomId());
            member.setUserId(memberEntity.getUserId());
            members.add(member);
        }
        chatDao.insertRoomMembers(members);
        memberEntities.forEach(memberEntity -> memberEntity.setJoinSeq(0L));
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
    public void insertUserRooms(List<UserRoomEntity> userRoomEntities) {
        List<UserRoom> userRooms = new ArrayList<>(userRoomEntities.size());
        for (UserRoomEntity userRoomEntity : userRoomEntities) {
            UserRoom userRoom = new UserRoom();
            userRoom.setRoomId(userRoomEntity.getRoomId());
            userRoom.setUserId(userRoomEntity.getUserId());
            userRooms.add(userRoom);
        }
        chatDao.insertUserRooms(userRooms);
        userRoomEntities.forEach(userRoomEntity -> userRoomEntity.setReadSeq(0L));
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
    public void hideRoom(String roomId, String userId) {
        chatDao.updateUserRoomHiddenAt(roomId, userId);
    }

    /** 名册成员集：会话级批量写的驱动来源（「这个会话有谁」只从名册取） */
    private List<String> rosterMemberIds(String roomId) {
        return chatDao.listRoomMembers(roomId).stream().map(RoomMember::getUserId).toList();
    }

    // ===== message =====

    @Override
    public void insertMessage(MessageEntity messageEntity) {
        // 业务 ID 应用层 insert 前发号、随行一次写入（timeline.msg_id 外联的就是这个业务键）；
        // create_time/update_time 由 SQL NOW() 落库，createdAt 由调用方点查回读补齐
        messageEntity.setMessageId(UUID.randomUUID().toString());
        Message message = new Message();
        message.setMsgId(messageEntity.getMessageId());
        message.setRoomId(messageEntity.getRoomId());
        message.setSeq(messageEntity.getSeq());
        message.setFromUserId(messageEntity.getFromUserId());
        message.setMsgType(messageEntity.getMsgType().name());
        message.setContent(messageEntity.getContent());
        message.setClientMsgId(messageEntity.getClientMsgId());
        message.setRefType(messageEntity.getRefType() == null ? null : messageEntity.getRefType().name());
        message.setRefId(messageEntity.getRefId());
        message.setState(MsgStatusVO.NORMAL.name());
        chatDao.insertMessage(message);
        messageEntity.setStatus(MsgStatusVO.NORMAL);
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
    public boolean recallMessage(String roomId, Long seq, RecallByVO recalledBy) {
        return chatDao.recallMessage(roomId, seq, recalledBy.name()) > 0;
    }

    @Override
    public MessageEntity findMessageById(String messageId) {
        return toMessageEntity(chatDao.selectMessageById(messageId));
    }

    @Override
    public List<MessageEntity> listMessagesBefore(String roomId, Long beforeSeq, int limit) {
        return chatDao.selectMessagesBefore(roomId, beforeSeq, limit).stream()
                .map(this::toMessageEntity)
                .toList();
    }

    // ===== timeline =====

    @Override
    public void insertTimelines(List<TimelineEntity> timelineEntities) {
        if (timelineEntities == null || timelineEntities.isEmpty()) {
            return;
        }
        List<Timeline> timelines = timelineEntities.stream().map(timelineEntity -> {
            Timeline timeline = new Timeline();
            timeline.setUserId(timelineEntity.getUserId());
            timeline.setRoomId(timelineEntity.getRoomId());
            timeline.setSeq(timelineEntity.getSeq());
            timeline.setMsgId(timelineEntity.getMsgId());
            timeline.setDeliverySeq(timelineEntity.getDeliverySeq());
            return timeline;
        }).toList();
        chatDao.insertTimelines(timelines);
    }

    @Override
    public List<TimelineItemVO> listTimelineAfter(String userId, long afterDeliverySeq, int limit) {
        // 增量补拉两段查（SQL 层零 JOIN）：timeline 投递行扫描 → msg_id 集批量回查消息全量，
        // 按投递序拼装。消息行缺失 = 原 INNER JOIN 语义（timeline 行在而消息行缺失属漂移），跳过
        List<TimelineDeliveryRow> rows = chatDao.selectTimelineRows(userId, afterDeliverySeq, limit);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, Message> messagesById = chatDao.selectMessagesByIds(
                rows.stream().map(TimelineDeliveryRow::getMsgId).toList()).stream()
                .collect(Collectors.toMap(Message::getMsgId, Function.identity(), (a, b) -> a));
        List<TimelineItemVO> items = new ArrayList<>(rows.size());
        for (TimelineDeliveryRow row : rows) {
            Message message = messagesById.get(row.getMsgId());
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

    private RoomListItemVO toRoomListItemVO(RoomListItem roomListItem) {
        RoomListItemVO roomListItemVO = new RoomListItemVO();
        roomListItemVO.setRoomId(roomListItem.getRoomId());
        roomListItemVO.setPeerUserId(roomListItem.getPeerUserId());
        roomListItemVO.setSource(roomListItem.getSource() == null ? null : RoomSourceEnum.valueOf(roomListItem.getSource()));
        roomListItemVO.setCreatorId(roomListItem.getCreatorId());
        roomListItemVO.setLastMsgTime(roomListItem.getLastMsgTime());
        roomListItemVO.setUnread(roomListItem.getLastSeq() - roomListItem.getReadSeq());
        return roomListItemVO;
    }

    private RoomEntity toRoomEntity(Room room) {
        if (room == null) {
            return null;
        }
        RoomEntity roomEntity = new RoomEntity();
        roomEntity.setRoomId(room.getRoomId());
        roomEntity.setSource(room.getSource() == null ? null : RoomSourceEnum.valueOf(room.getSource()));
        roomEntity.setCreatorId(room.getCreatorId());
        roomEntity.setLastSeq(room.getLastSeq());
        roomEntity.setCreatedAt(room.getCreateTime());
        return roomEntity;
    }

    private RoomMemberEntity toRoomMemberEntity(RoomMember member) {
        if (member == null) {
            return null;
        }
        RoomMemberEntity memberEntity = new RoomMemberEntity();
        memberEntity.setId(member.getId());
        memberEntity.setRoomId(member.getRoomId());
        memberEntity.setUserId(member.getUserId());
        memberEntity.setJoinSeq(member.getJoinSeq());
        memberEntity.setCreatedAt(member.getCreateTime());
        return memberEntity;
    }

    private UserRoomEntity toUserRoomEntity(UserRoom userRoom) {
        if (userRoom == null) {
            return null;
        }
        UserRoomEntity userRoomEntity = new UserRoomEntity();
        userRoomEntity.setId(userRoom.getId());
        userRoomEntity.setRoomId(userRoom.getRoomId());
        userRoomEntity.setUserId(userRoom.getUserId());
        userRoomEntity.setReadSeq(userRoom.getReadSeq());
        userRoomEntity.setHiddenAt(userRoom.getHiddenAt());
        userRoomEntity.setCreatedAt(userRoom.getCreateTime());
        return userRoomEntity;
    }

    private MessageEntity toMessageEntity(Message message) {
        if (message == null) {
            return null;
        }
        MessageEntity messageEntity = new MessageEntity();
        messageEntity.setMessageId(message.getMsgId());
        messageEntity.setRoomId(message.getRoomId());
        messageEntity.setSeq(message.getSeq());
        messageEntity.setFromUserId(message.getFromUserId());
        messageEntity.setMsgType(MsgTypeVO.valueOf(message.getMsgType()));
        messageEntity.setContent(message.getContent());
        messageEntity.setClientMsgId(message.getClientMsgId());
        messageEntity.setRefType(message.getRefType() == null ? null : CardRefTypeVO.valueOf(message.getRefType()));
        messageEntity.setRefId(message.getRefId());
        messageEntity.setStatus(MsgStatusVO.valueOf(message.getState()));
        messageEntity.setRecalledAt(message.getRecalledAt());
        messageEntity.setRecalledBy(message.getRecalledBy() == null ? null : RecallByVO.valueOf(message.getRecalledBy()));
        messageEntity.setCreatedAt(message.getCreateTime());
        return messageEntity;
    }
}
