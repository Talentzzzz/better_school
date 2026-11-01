package com.betterschool.domain.chat.adapter.repository;

import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.entity.RoomEntity;
import com.betterschool.domain.chat.model.entity.RoomMemberEntity;
import com.betterschool.domain.chat.model.entity.TimelineEntity;
import com.betterschool.domain.chat.model.valobj.ChatMessagePushMessage;
import com.betterschool.domain.chat.model.valobj.ChatReadPushMessage;
import com.betterschool.domain.chat.model.valobj.ChatRecallPushMessage;
import com.betterschool.domain.chat.model.valobj.MsgTypeVO;
import com.betterschool.domain.chat.model.valobj.RecallByVO;
import com.betterschool.domain.chat.model.valobj.RoomListItemVO;
import com.betterschool.domain.chat.model.valobj.TimelineItemVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * chat 仓储（room/message/timeline 模型）：读路径原语 + 写路径原子用例。
 * <p>
 * 「原子用例」= 一次操作内的多次读改写，实现内以编程式事务（PROPAGATION_REQUIRED）收成一个方法，
 * 与调用方既有事务合流；域服务只构造实体与参数后调一次，不再自持事务。
 * 取号（increaseLastSeq/increaseUserSeq）与消息、时间线写入必须同事务，回滚即无号无痕。
 */
public interface IChatRepository {

    // ===== 原子用例（仓储内事务） =====

    /**
     * 建 1v1 会话 + 两名成员 + 配对行（一个原子用例）：成员 = 首建发起人（room.initiatorId）+ 对家；
     * 配对行（direct_room_pair.uk_pair）是事务收尾闸门，同一对人并发建会话时后到者整事务回滚
     * room_id 由 insertRoom 在实现内应用层发号，成员行在实现内构造
     *
     * @return 落库后的会话（含 roomId）
     */
    RoomEntity createDirectRoom(RoomEntity room, String targetUserId);

    /**
     * 发送落库段（一个原子用例）：取号 → 落消息 → 扇出时间线（per-user delivery_seq 取号）
     * → 清 hidden 弹回 → 刷 room_member 列表副本（水位 + 预览三件套）→ 写 MSG 推送事件。
     * 预览文案与推送载荷（发送者昵称头像等）由调用方备好，实现内只补事务中生成的消息 ID/序号/时间
     *
     * @return 落库后的完整消息体（含 roomId/seq/messageId/createdAt）
     */
    MessageEntity deliverMessage(MessageEntity message, String preview, ChatMessagePushMessage pushMessage);

    /**
     * 已读水位线推进 + READ 事件（一个原子用例）。
     * 守卫 UPDATE 只前进：仅水位线实际前进才写 outbox（并发/重复打开幂等无噪音）
     */
    void advanceReadWatermark(String roomId, String userId, long readSeq);

    /**
     * 撤回 + 预览替换 + RECALL 事件（一个原子用例）。
     * 条件 UPDATE（status=NORMAL 才生效）：并发撤回/强撤撞自行撤回时落空即静默，不重复投递事件
     */
    void recallMessageWithEvent(String roomId, Long seq, LocalDateTime recalledAt, RecallByVO recalledBy, String preview);

    // ===== room（会话本体） =====

    void insertRoom(RoomEntity room);

    RoomEntity findRoomById(String roomId);

    /** 1v1 查重：同一对人恒一条会话（direct_room_pair.uk_pair） */
    RoomEntity findDirectRoom(String userMin, String userMax);

    /** 发消息事务内原子取号：UPDATE last_seq = last_seq + 1 后读回，失败回滚无空洞 */
    long increaseLastSeq(String roomId);

    /**
     * 发送事务内刷成员行列表副本（本票过渡：与取号同事务，扩散消费端单一写入方归票 02）：
     * last_seq_copy GREATEST 只进不退 + 预览三件套整行刷新（恒两行成员，一次覆盖双方）
     */
    void updateMemberCopies(String roomId, long seq, MsgTypeVO msgType, String preview, LocalDateTime lastMsgAt);

    /**
     * 会话列表查询（idx_user_msg 主路径，用户维度单行可算）：排除 hidden_at 非空，
     * 按 last_msg_at 倒序分页；unread 由调用方按 last_seq_copy − last_read_seq 拼装
     *
     * @return 按 last_msg_at 倒序的会话概要（含本人在会话的水位线）
     */
    List<RoomListItemVO> listRoomItems(String userId, int offset, int limit);

    long countRoomsForUser(String userId);

    /** 会话历史分页（uk_room_seq 主路径）：seq &lt; beforeSeq 倒序取 limit 条；beforeSeq 为 NULL 取最新一页 */
    List<MessageEntity> listMessagesBefore(String roomId, Long beforeSeq, int limit);

    /**
     * 增量补拉（timeline.uk_user_delivery 主路径）：delivery_seq &gt; afterDeliverySeq 升序取 limit 条，
     * JOIN message 取消息全量。timeline 永不删除，游标永远有效
     */
    List<TimelineItemVO> listTimelineAfter(String userId, long afterDeliverySeq, int limit);

    // ===== room_member（成员状态） =====

    void insertRoomMembers(List<RoomMemberEntity> members);

    RoomMemberEntity findRoomMember(String roomId, String userId);

    List<RoomMemberEntity> listRoomMembers(String roomId);

    long countRoomMembers(String roomId);

    /**
     * 已读水位线只前进不回退（last_read_seq &lt; 目标才更新，幂等）。
     *
     * @return 是否实际推进（0 = 水位线已不低于目标，调用方据此决定是否投递 READ 事件防噪音）
     */
    boolean updateLastReadSeq(String roomId, String userId, long lastReadSeq);

    /** 新消息扇出时清空该会话全部成员的隐藏位（删除的会话弹回，微信行为） */
    void clearHidden(String roomId);

    /** 用户侧隐藏会话（置 hidden_at）；新消息 clearHidden 弹回 */
    void hideRoom(String roomId, String userId, LocalDateTime hiddenAt);

    // ===== message（内容中心） =====

    void insertMessage(MessageEntity message);

    MessageEntity findMessage(String roomId, Long seq);

    /** 发送幂等回查：撞 uk_client_msg 后按幂等键取回原消息，收敛同一条 */
    MessageEntity findMessageByClientMsgId(String roomId, String clientMsgId);

    /**
     * 撤回三件套：status=RECALLED + recalled_at + recalled_by，占 seq 不回退。
     * 条件 UPDATE（status=NORMAL 才生效）：用户撤回与强制撤回并发先到先得
     *
     * @return 是否实际撤回（0 = 已被撤回，调用方据此跳过预览替换与 RECALL 事件）
     */
    boolean recallMessage(String roomId, Long seq, LocalDateTime recalledAt, RecallByVO recalledBy);

    /** 举报回查：按消息全局主键取消息（MESSAGE 举报定位 / 强制撤回入口） */
    MessageEntity findMessageById(String messageId);

    /**
     * 撤回后替换会话列表预览（用户维度成员行）：仅当该消息仍是会话最后一条（last_seq_copy 守卫）才替换，
     * 并发新消息到达时守卫落空，不覆盖新消息预览
     */
    void markLastMsgRecalled(String roomId, long seq, String preview);

    // ===== timeline（不可变瘦收件箱） =====

    /** 多值 INSERT 扇出：一条消息 N 个成员 N 行，只插不改 */
    void insertTimelines(List<TimelineEntity> timelines);

    // ===== user_seq（per-user 投递序号计数器） =====

    /**
     * 扇出事务内取号：计数器 +N 读回，行锁到提交保证 per-user 单调且=提交序。
     * 计数器行不存在时自动建（seq 从 N 起算）。
     *
     * @return 取号后的计数值，本次分配区间为 [返回值-N+1, 返回值]
     */
    long increaseUserSeq(String userId, int n);

    // ===== outbox（推送事件） =====

    /** 发送事务内写 MSG 推送事件（outbox），事务提交后异步发 MQ，由 WS 推送消费（票 04） */
    void savePushMessage(ChatMessagePushMessage message);

    /** 水位线推进事务内写 READ 推送事件（outbox），仅水位线实际前进时调用（幂等无噪音） */
    void saveReadMessage(ChatReadPushMessage message);

    /** 撤回事务内写 RECALL 轻事件（outbox）：载荷只带 roomId+seq 定位，内容终态以拉取为准 */
    void saveRecallMessage(ChatRecallPushMessage message);
}
