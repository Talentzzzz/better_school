package com.betterschool.infrastructure.adapter.repository.chat;

import com.betterschool.domain.chat.adapter.repository.IChatRepository;
import com.betterschool.infrastructure.ws.IWsService;
import com.betterschool.infrastructure.ws.wsframe.WsFrameFactory;
import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.entity.RoomEntity;
import com.betterschool.domain.chat.model.entity.RoomMemberEntity;
import com.betterschool.domain.chat.model.entity.TimelineEntity;
import com.betterschool.domain.chat.model.entity.UserRoomEntity;
import com.betterschool.domain.chat.model.valobj.CardRefTypeEnum;
import com.betterschool.domain.chat.model.valobj.DirectRoomPairVO;
import com.betterschool.domain.chat.model.valobj.ChatMessagePushEvent;
import com.betterschool.domain.chat.model.valobj.ChatReadPushEvent;
import com.betterschool.domain.chat.model.valobj.ChatRecallPushEvent;
import com.betterschool.domain.chat.model.valobj.MessagePreviewVO;
import com.betterschool.domain.chat.model.valobj.MsgStatusEnum;
import com.betterschool.domain.chat.model.valobj.MsgTypeEnum;
import com.betterschool.domain.chat.model.valobj.RecallByEnum;
import com.betterschool.domain.chat.model.valobj.RoomSourceEnum;
import com.betterschool.domain.chat.model.valobj.RoomListItemVO;
import com.betterschool.domain.chat.model.valobj.TimelineItemVO;
import com.betterschool.domain.chat.model.valobj.TimelinePageVO;
import com.betterschool.infrastructure.dao.chat.IMessageDao;
import com.betterschool.infrastructure.dao.chat.IRoomDao;
import com.betterschool.infrastructure.dao.chat.IRoomMemberDao;
import com.betterschool.infrastructure.dao.chat.ITimelineDao;
import com.betterschool.infrastructure.dao.chat.IUserPairRoomDao;
import com.betterschool.infrastructure.dao.chat.IUserRoomDao;
import com.betterschool.infrastructure.dao.chat.IUserSeqDao;
import com.betterschool.infrastructure.dao.chat.po.UserPairRoom;
import com.betterschool.infrastructure.dao.chat.po.Message;
import com.betterschool.infrastructure.dao.chat.po.MessagePreview;
import com.betterschool.infrastructure.dao.chat.po.Room;
import com.betterschool.infrastructure.dao.chat.po.RoomListItem;
import com.betterschool.infrastructure.dao.chat.po.RoomMember;
import com.betterschool.infrastructure.dao.chat.po.RoomPeerRow;
import com.betterschool.infrastructure.dao.chat.po.TimelineDeliveryRow;
import com.betterschool.infrastructure.dao.chat.po.Timeline;
import com.betterschool.infrastructure.dao.chat.po.UserRoom;
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

    private final IRoomDao chatRoomDao;
    private final IUserPairRoomDao chatUserPairRoomDao;
    private final IRoomMemberDao chatRoomMemberDao;
    private final IUserRoomDao chatUserRoomDao;
    private final IMessageDao chatMessageDao;
    private final ITimelineDao chatTimelineDao;
    private final IUserSeqDao chatUserSeqDao;
    private final TransactionTemplate transactionTemplate;
    private final IWsService wsService;

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
        // 名册与用户状态成对写入：1v1 恒两行名册（join_seq=0 入群水位）+ 两行 user_room（read_seq=0；
        // hidden_at 由 SQL NOW() 建行即置——只有发过消息，会话才出现在双方列表，首条消息弹回）
        RoomMember creatorMember = new RoomMember();
        creatorMember.setRoomId(roomId);
        creatorMember.setUserId(roomEntity.getCreatorId());
        RoomMember targetMember = new RoomMember();
        targetMember.setRoomId(roomId);
        targetMember.setUserId(targetUserId);
        List<RoomMember> roomMemberList = List.of(creatorMember, targetMember);
        UserRoom creatorUserRoom = new UserRoom();
        creatorUserRoom.setRoomId(roomId);
        creatorUserRoom.setUserId(roomEntity.getCreatorId());
        UserRoom targetUserRoom = new UserRoom();
        targetUserRoom.setRoomId(roomId);
        targetUserRoom.setUserId(targetUserId);
        List<UserRoom> userRoomList = List.of(creatorUserRoom, targetUserRoom);
        // 配对行收尾闸门：撞 uk_pair（同一对人恒一行）即整事务回滚，
        // 服务层捕获 DuplicateKeyException 后经 pair 表幂等回读既有会话
        // user_id 是 UUID 字符串，配对键按字符串字典序取规范序（计算收敛在 DirectRoomPairVO，与查重同口径）
        DirectRoomPairVO directRoomPairVO = DirectRoomPairVO.canonicalOf(roomEntity.getCreatorId(), targetUserId);
        UserPairRoom userPairRoom = new UserPairRoom();
        userPairRoom.setUserMin(directRoomPairVO.getUserMin());
        userPairRoom.setUserMax(directRoomPairVO.getUserMax());
        userPairRoom.setRoomId(roomId);

        transactionTemplate.executeWithoutResult(status -> {
            chatRoomDao.insertRoom(room);
            chatRoomMemberDao.insertRoomMembers(roomMemberList);
            chatUserRoomDao.insertUserRooms(userRoomList);
            chatUserPairRoomDao.insertUserPairRoom(userPairRoom);
        });
        return roomEntity;
    }

    @Override
    public MessageEntity deliverMessage(MessageEntity messageEntity, ChatMessagePushEvent chatMessagePushEvent) {
        // 事务体无法再往外挪「对象组装」：seq/deliverySeq/createdAt 全是事务内 DB 取号与 NOW() 回读的
        // 产物，逐行组装消费的就是这些值（对照 createDirectRoom：键全为应用层 UUID，才能 PO 事务外构建、
        // 事务内只做写入）。事务体即「取号 → 写入 → 回读补齐」的原子 dao 序列；
        // 推送由调用方在本方法返回（事务已提交）后执行，推送失败由客户端 (roomId, seq) 增量补拉兜底
        return transactionTemplate.execute(status -> {
            long seq = increaseLastSeq(messageEntity.getRoomId());
            messageEntity.setSeq(seq);
            insertMessage(messageEntity);

            // 名册事务内一次读取：扇出/刷排序键+清隐藏位两个写路径共用同一成员集
            //（同事务同快照，此前三处各自重读属冗余）
            List<String> memberIdList = rosterMemberIds(messageEntity.getRoomId());

            // 扇出：按名册逐成员一行 timeline，per-user delivery_seq 取号（被移除成员停止扇出）
            insertTimelinesForRoster(memberIdList, messageEntity, seq);
            // create_time 由 SQL NOW() 落库，点查回读补齐
            messageEntity.setCreatedAt(findMessage(messageEntity.getRoomId(), seq).getCreatedAt());

            // 列表排序键落用户维度 user_room 行（票 02 预览去副本：只刷 last_msg_time，预览读时现算），
            // 同一条 UPDATE 清隐藏位——新消息必弹回（删除的会话弹回列表顶部，微信行为）
            updateUserRoomOnMessage(messageEntity.getRoomId(), memberIdList, messageEntity.getCreatedAt());

            // 推送载荷的事务内生成字段（序号/主键/时间）在这里补齐，返回后由调用方直接推送
            chatMessagePushEvent.setSeq(seq);
            chatMessagePushEvent.setMessageId(messageEntity.getMessageId());
            chatMessagePushEvent.setCreatedAt(messageEntity.getCreatedAt());
            return messageEntity;
        });
    }

    /** 扇出：给定成员集逐成员 delivery_seq 取号后成行写入（取号是 dao 副作用，须随事务回滚） */
    private void insertTimelinesForRoster(List<String> memberIdList, MessageEntity messageEntity, long seq) {
        List<TimelineEntity> timelineEntityList = new ArrayList<>(memberIdList.size());
        for (String memberId : memberIdList) {
            TimelineEntity timelineEntity = new TimelineEntity();
            timelineEntity.setUserId(memberId);
            timelineEntity.setRoomId(messageEntity.getRoomId());
            timelineEntity.setSeq(seq);
            timelineEntity.setMsgId(messageEntity.getMessageId());
            timelineEntity.setDeliverySeq(increaseUserSeq(memberId, 1));
            timelineEntityList.add(timelineEntity);
        }
        insertTimelines(timelineEntityList);
    }

    @Override
    public boolean advanceReadWatermark(String roomId, String userId, long readSeq) {
        // 守卫 UPDATE 只前进：落空（并发/重复打开）即 false，调用方不产生 READ 推送
        Boolean advanced = transactionTemplate.execute(status -> updateUserReadSeq(roomId, userId, readSeq));
        return Boolean.TRUE.equals(advanced);
    }

    @Override
    public boolean recallMessageWithEvent(String roomId, Long seq, RecallByEnum recallByVO) {
        // 条件 UPDATE（status=NORMAL 才生效）：并发撤回先到先得，落空即已被撤回，幂等静默
        Boolean recalled = transactionTemplate.execute(status -> recallMessage(roomId, seq, recallByVO));
        return Boolean.TRUE.equals(recalled);
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
        chatRoomDao.insertRoom(room);
        roomEntity.setLastSeq(0L);
    }

    @Override
    public RoomEntity findRoomById(String roomId) {
        return toRoomEntity(chatRoomDao.selectRoomById(roomId));
    }

    @Override
    public RoomEntity findDirectRoom(String userMin, String userMax) {
        String roomId = chatUserPairRoomDao.selectUserPairRoomId(userMin, userMax);
        return roomId == null ? null : findRoomById(roomId);
    }

    @Override
    public long increaseLastSeq(String roomId) {
        chatRoomDao.increaseLastSeq(roomId);
        Long lastSeq = chatRoomDao.selectLastSeq(roomId);
        if (lastSeq == null) {
            throw new IllegalStateException("会话不存在，无法取号：" + roomId);
        }
        return lastSeq;
    }

    @Override
    public void updateUserRoomOnMessage(String roomId, List<String> memberIdList, LocalDateTime lastMsgTime) {
        for (String memberId : memberIdList) {
            chatUserRoomDao.updateUserRoomOnMessage(roomId, memberId, lastMsgTime);
        }
    }

    @Override
    public List<RoomListItemVO> listRoomItems(String userId, int offset, int limit) {
        // 会话列表四段查（SQL 层零 JOIN）：①本人 user_room 行分页 → ②room 行批量回查 → ③名册回查对家
        // → ④按 (room_id, room.last_seq) 逐会话点查 message 最新一条（预览真源，票 02 预览去副本），
        // Java 按会话拼装。room 行或对家行缺失 = 原 INNER JOIN 语义，该会话不出现
        List<RoomListItem> roomListItemList = chatUserRoomDao.selectUserRoomPage(userId, offset, limit);
        if (roomListItemList.isEmpty()) {
            return List.of();
        }
        List<String> roomIdList = roomListItemList.stream().map(RoomListItem::getRoomId).toList();
        Map<String, Room> roomsById = chatRoomDao.selectRoomsByIds(roomIdList).stream()
                .collect(Collectors.toMap(Room::getRoomId, Function.identity(), (a, b) -> a));
        Map<String, String> peerByRoom = chatRoomMemberDao.selectPeerMembers(roomIdList, userId).stream()
                .collect(Collectors.toMap(RoomPeerRow::getRoomId, RoomPeerRow::getUserId, (a, b) -> a));
        List<RoomListItemVO> roomListItemVOList = new ArrayList<>(roomListItemList.size());
        for (RoomListItem roomListItem : roomListItemList) {
            Room room = roomsById.get(roomListItem.getRoomId());
            String peerUserId = peerByRoom.get(roomListItem.getRoomId());
            if (room == null || peerUserId == null) {
                continue;
            }
            roomListItem.setSource(room.getSource());
            roomListItem.setCreatorId(room.getCreatorId());
            roomListItem.setLastSeq(room.getLastSeq());
            roomListItem.setPeerUserId(peerUserId);
            RoomListItemVO roomListItemVO = toRoomListItemVO(roomListItem);
            if (room.getLastSeq() != null && room.getLastSeq() > 0) {
                // 四段查之④ 预览真源：按 (room_id, room.last_seq) 逐会话点查最新一条消息的预览四列
                //（last_seq=0 尚无消息不查；§9.7 批量回查的例外：锚点是 (room_id, seq) 复合键，
                // msg_id 要点查后才知，无法先集后查——逐会话走 uk_room_seq 点查原语，
                // 上限 = 页大小（controller MAX_PAGE_SIZE=200），非无界 N+1）
                roomListItemVO.setLastPreview(toMessagePreviewVO(
                        chatMessageDao.selectMessagePreview(roomListItem.getRoomId(), room.getLastSeq())));
            }
            roomListItemVOList.add(roomListItemVO);
        }
        return roomListItemVOList;
    }

    // ===== room_member（会话维度名册：「这个会话有谁」） =====

    @Override
    public void insertRoomMembers(List<RoomMemberEntity> roomMemberEntityList) {
        List<RoomMember> roomMemberList = new ArrayList<>(roomMemberEntityList.size());
        for (RoomMemberEntity roomMemberEntity : roomMemberEntityList) {
            RoomMember roomMember = new RoomMember();
            roomMember.setRoomId(roomMemberEntity.getRoomId());
            roomMember.setUserId(roomMemberEntity.getUserId());
            roomMemberList.add(roomMember);
        }
        chatRoomMemberDao.insertRoomMembers(roomMemberList);
        roomMemberEntityList.forEach(memberEntity -> memberEntity.setJoinSeq(0L));
    }

    @Override
    public List<RoomMemberEntity> listRoomMembers(String roomId) {
        return chatRoomMemberDao.listRoomMembers(roomId).stream().map(this::toRoomMemberEntity).toList();
    }

    @Override
    public long countRoomMembers(String roomId) {
        return chatRoomMemberDao.countRoomMembers(roomId);
    }

    // ===== user_room（用户维度会话状态） =====

    @Override
    public void insertUserRooms(List<UserRoomEntity> userRoomEntityList) {
        List<UserRoom> userRoomList = new ArrayList<>(userRoomEntityList.size());
        for (UserRoomEntity userRoomEntity : userRoomEntityList) {
            UserRoom userRoom = new UserRoom();
            userRoom.setRoomId(userRoomEntity.getRoomId());
            userRoom.setUserId(userRoomEntity.getUserId());
            userRoomList.add(userRoom);
        }
        chatUserRoomDao.insertUserRooms(userRoomList);
        userRoomEntityList.forEach(userRoomEntity -> userRoomEntity.setReadSeq(0L));
    }

    @Override
    public UserRoomEntity findUserRoom(String roomId, String userId) {
        return toUserRoomEntity(chatUserRoomDao.selectUserRoom(roomId, userId));
    }

    @Override
    public List<UserRoomEntity> listUserRooms(String roomId, List<String> userIdList) {
        if (userIdList == null || userIdList.isEmpty()) {
            return List.of();
        }
        return chatUserRoomDao.selectUserRooms(roomId, userIdList).stream().map(this::toUserRoomEntity).toList();
    }

    @Override
    public boolean updateUserReadSeq(String roomId, String userId, long readSeq) {
        return chatUserRoomDao.updateUserRoomReadSeq(roomId, userId, readSeq) > 0;
    }

    @Override
    public void clearHidden(String roomId, List<String> memberIdList) {
        for (String memberId : memberIdList) {
            chatUserRoomDao.clearUserRoomHidden(roomId, memberId);
        }
    }

    @Override
    public void hideRoom(String roomId, String userId) {
        chatUserRoomDao.updateUserRoomHiddenAt(roomId, userId);
    }

    /** 名册成员集：「这个会话有谁」只从名册取（发送事务内一次读取，三个写路径共用） */
    private List<String> rosterMemberIds(String roomId) {
        return chatRoomMemberDao.listRoomMembers(roomId).stream().map(RoomMember::getUserId).toList();
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
        message.setState(MsgStatusEnum.NORMAL.name());
        chatMessageDao.insertMessage(message);
        messageEntity.setStatus(MsgStatusEnum.NORMAL);
    }

    @Override
    public MessageEntity findMessage(String roomId, Long seq) {
        return toMessageEntity(chatMessageDao.selectMessage(roomId, seq));
    }

    @Override
    public MessageEntity findMessageByClientMsgId(String roomId, String clientMsgId) {
        return toMessageEntity(chatMessageDao.selectMessageByClientMsgId(roomId, clientMsgId));
    }

    @Override
    public boolean recallMessage(String roomId, Long seq, RecallByEnum recallByVO) {
        return chatMessageDao.recallMessage(roomId, seq, recallByVO.name()) > 0;
    }

    @Override
    public MessageEntity findMessageById(String messageId) {
        return toMessageEntity(chatMessageDao.selectMessageById(messageId));
    }

    @Override
    public List<MessageEntity> listMessagesBefore(String roomId, Long beforeSeq, int limit) {
        return chatMessageDao.selectMessagesBefore(roomId, beforeSeq, limit).stream()
                .map(this::toMessageEntity)
                .toList();
    }

    // ===== timeline =====

    @Override
    public void insertTimelines(List<TimelineEntity> timelineEntityList) {
        if (timelineEntityList == null || timelineEntityList.isEmpty()) {
            return;
        }
        List<Timeline> timelineList = timelineEntityList.stream().map(timelineEntity -> {
            Timeline timeline = new Timeline();
            timeline.setUserId(timelineEntity.getUserId());
            timeline.setRoomId(timelineEntity.getRoomId());
            timeline.setSeq(timelineEntity.getSeq());
            timeline.setMsgId(timelineEntity.getMsgId());
            timeline.setDeliverySeq(timelineEntity.getDeliverySeq());
            return timeline;
        }).toList();
        chatTimelineDao.insertTimelines(timelineList);
    }

    @Override
    public TimelinePageVO listTimelineAfter(String userId, long deliverySeqCursor, int limit) {
        // 增量补拉两段查（SQL 层零 JOIN）：timeline 投递行扫描 → msg_id 集批量回查消息全量，
        // 按投递序拼装。消息行缺失 = 原 INNER JOIN 语义（timeline 行在而消息行缺失属漂移），跳过
        List<TimelineDeliveryRow> timelineDeliveryRowList = chatTimelineDao.selectTimelineRows(userId, deliverySeqCursor, limit);
        if (timelineDeliveryRowList.isEmpty()) {
            // 空页不动游标：原样回传入参，hasMore=false 即追平
            return new TimelinePageVO(List.of(), deliverySeqCursor, Boolean.FALSE);
        }
        Map<String, Message> messagesById = chatMessageDao.selectMessagesByIds(
                timelineDeliveryRowList.stream().map(TimelineDeliveryRow::getMsgId).toList()).stream()
                .collect(Collectors.toMap(Message::getMsgId, Function.identity(), (a, b) -> a));
        List<TimelineItemVO> timelineItemVOList = new ArrayList<>(timelineDeliveryRowList.size());
        for (TimelineDeliveryRow timelineDeliveryRow : timelineDeliveryRowList) {
            Message message = messagesById.get(timelineDeliveryRow.getMsgId());
            if (message == null) {
                continue;
            }
            timelineItemVOList.add(new TimelineItemVO(toMessageEntity(message), timelineDeliveryRow.getDeliverySeq()));
        }
        // 游标 = 扫描边界（本页最后一行），不是「最后一条拼装成功的条目」：漂移行只少返回一条，
        // 不会让游标停在它前面（否则连续 ≥ limit 行漂移就把游标永久钉死）
        long nextCursor = timelineDeliveryRowList.get(timelineDeliveryRowList.size() - 1).getDeliverySeq();
        // 填满一页即「可能还有」：交界处多一次空请求，换游标严格单调
        return new TimelinePageVO(timelineItemVOList, nextCursor, timelineDeliveryRowList.size() >= limit);
    }

    // ===== user_seq =====

    @Override
    public long increaseUserSeq(String userId, int n) {
        chatUserSeqDao.upsertIncreaseUserSeq(userId, n);
        Long seq = chatUserSeqDao.selectUserSeq(userId);
        if (seq == null) {
            throw new IllegalStateException("投递序号计数器取号失败：" + userId);
        }
        return seq;
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

    private RoomMemberEntity toRoomMemberEntity(RoomMember roomMember) {
        if (roomMember == null) {
            return null;
        }
        RoomMemberEntity roomMemberEntity = new RoomMemberEntity();
        roomMemberEntity.setId(roomMember.getId());
        roomMemberEntity.setRoomId(roomMember.getRoomId());
        roomMemberEntity.setUserId(roomMember.getUserId());
        roomMemberEntity.setJoinSeq(roomMember.getJoinSeq());
        roomMemberEntity.setCreatedAt(roomMember.getCreateTime());
        return roomMemberEntity;
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
        messageEntity.setMsgType(MsgTypeEnum.valueOf(message.getMsgType()));
        messageEntity.setContent(message.getContent());
        messageEntity.setClientMsgId(message.getClientMsgId());
        messageEntity.setRefType(message.getRefType() == null ? null : CardRefTypeEnum.valueOf(message.getRefType()));
        messageEntity.setRefId(message.getRefId());
        messageEntity.setStatus(MsgStatusEnum.valueOf(message.getState()));
        messageEntity.setRecalledAt(message.getRecalledAt());
        messageEntity.setRecalledBy(message.getRecalledBy() == null ? null : RecallByEnum.valueOf(message.getRecalledBy()));
        messageEntity.setCreatedAt(message.getCreateTime());
        return messageEntity;
    }

    /** 预览投影：state 在出库处收敛成 recalled 布尔，渲染器不碰消息状态机 */
    private MessagePreviewVO toMessagePreviewVO(MessagePreview messagePreview) {
        if (messagePreview == null) {
            return null;
        }
        MessagePreviewVO messagePreviewVO = new MessagePreviewVO();
        messagePreviewVO.setMsgType(MsgTypeEnum.valueOf(messagePreview.getMsgType()));
        messagePreviewVO.setContent(messagePreview.getContent());
        messagePreviewVO.setRefType(messagePreview.getRefType() == null
                ? null : CardRefTypeEnum.valueOf(messagePreview.getRefType()));
        messagePreviewVO.setRecalled(MsgStatusEnum.RECALLED.name().equals(messagePreview.getState()));
        return messagePreviewVO;
    }

    @Override
    public void pushMessage(String userId, ChatMessagePushEvent chatMessagePushEvent) {
        wsService.push(userId, WsFrameFactory.msg(chatMessagePushEvent));
    }

    @Override
    public void pushRead(String userId, ChatReadPushEvent chatReadPushEvent) {
        wsService.push(userId, WsFrameFactory.read(chatReadPushEvent));
    }

    @Override
    public void pushRecall(String userId, ChatRecallPushEvent chatRecallPushEvent) {
        wsService.push(userId, WsFrameFactory.recall(chatRecallPushEvent));
    }
}
