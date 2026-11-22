package com.betterschool.domain.chat.adapter.repository;

import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.entity.RoomEntity;
import com.betterschool.domain.chat.model.entity.RoomMemberEntity;
import com.betterschool.domain.chat.model.entity.TimelineEntity;
import com.betterschool.domain.chat.model.entity.UserRoomEntity;
import com.betterschool.domain.chat.model.valobj.ChatMessagePushEvent;
import com.betterschool.domain.chat.model.valobj.ChatReadPushEvent;
import com.betterschool.domain.chat.model.valobj.ChatRecallPushEvent;
import com.betterschool.domain.chat.model.valobj.RecallByVO;
import com.betterschool.domain.chat.model.valobj.RoomListItemVO;
import com.betterschool.domain.chat.model.valobj.TimelineItemVO;
import com.betterschool.domain.chat.model.valobj.TimelinePageVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * chat 仓储（room/user_room/room_member 名册/message/timeline 模型）：读路径原语 + 写路径原子用例。
 * <p>
 * 「原子用例」= 一次操作内的多次读改写，实现内以编程式事务（PROPAGATION_REQUIRED）收成一个方法，
 * 与调用方既有事务合流；域服务只构造实体与参数后调一次，不再自持事务。
 * 取号（increaseLastSeq/increaseUserSeq）与消息、时间线写入必须同事务，回滚即无号无痕。
 * <p>
 * 双维度拆分口径：user_room（用户维度，贴 user_id 片键）只装 per-user 状态；
 * 「这个会话有谁」一律走 room_member 名册（会话维度，贴 room_id 片键）——成员枚举/对家回查从名册走，
 * user_room 的会话级批量写随之改为「名册给定成员集、逐用户执行」
 */
public interface IChatRepository {

    // ===== 原子用例（仓储内事务） =====

    /**
     * 建 1v1 会话 + 两行名册 + 两行用户状态 + 配对行（一个原子用例）：成员 = 会话创建人（room.creatorId）+ 对家；
     * 名册行（join_seq=0）与 user_room 行（read_seq=0）同事务成对写入；
     * 配对行（user_pair_room.uk_pair）是事务收尾闸门，同一对人并发建会话时后到者整事务回滚
     * room_id 由 insertRoom 在实现内应用层发号，成员行在实现内构造
     *
     * @return 落库后的会话（含 roomId）
     */
    RoomEntity createDirectRoom(RoomEntity roomEntity, String targetUserId);

    /**
     * 发送落库段（一个原子用例）：取号 → 落消息 → 名册事务内一次读取 → 按成员集扇出时间线（per-user
     * delivery_seq 取号）→ 逐用户清 hidden 弹回 → 逐用户刷 user_room 列表排序键（last_msg_time；
     * 预览不落副本，读时现算）→ 写 MSG 推送事件。
     * 推送载荷（发送者昵称头像等）由调用方备好，实现内只补事务中生成的消息 ID/序号/时间
     *
     * @return 落库后的完整消息体（含 roomId/seq/messageId/createdAt）
     */
    MessageEntity deliverMessage(MessageEntity messageEntity, ChatMessagePushEvent chatMessagePushEvent);

    /**
     * 已读水位线推进 + READ 事件（一个原子用例）。
     * 守卫 UPDATE 只前进：仅水位线实际前进才写 outbox（并发/重复打开幂等无噪音）
     */
    void advanceReadWatermark(String roomId, String userId, long readSeq);

    /**
     * 撤回 + RECALL 事件（一个原子用例）：预览零维护写（读时按 message 真相现算，撤回即时反映）。
     * 条件 UPDATE（status=NORMAL 才生效）：并发撤回/强撤撞自行撤回时落空即静默，不重复投递事件；
     * recalled_at 由 SQL NOW() 落库（时刻一律数据库时钟）
     */
    void recallMessageWithEvent(String roomId, Long seq, RecallByVO recalledBy);

    // ===== room（会话本体） =====

    void insertRoom(RoomEntity roomEntity);

    RoomEntity findRoomById(String roomId);

    /** 1v1 查重：同一对人恒一条会话（user_pair_room.uk_pair） */
    RoomEntity findDirectRoom(String userMin, String userMax);

    /** 发消息事务内原子取号：UPDATE last_seq = last_seq + 1 后读回，失败回滚无空洞 */
    long increaseLastSeq(String roomId);

    /**
     * 发送事务内刷用户行列表排序键（与取号同事务）：last_msg_time 按给定成员集逐用户刷新（1v1 恒两行）；
     * 成员集由调用方给定（发送事务内名册一次读取，扇出/清隐藏/刷排序键共用同一成员集）；
     * 预览不落副本（读时按 message 真相现算），未读数不落副本，由 room.last_seq 现算
     */
    void updateUserRoomLastMsgTime(String roomId, List<String> memberIdList, LocalDateTime lastMsgTime);

    /**
     * 会话列表四段查（SQL 层零 JOIN）：排除 hidden_at 非空，按 last_msg_time 倒序分页；
     * unread = lastSeq − readSeq 与预览（message 最新一条的预览四列，lastPreview 中间态）由实现拼装，
     * 预览文案渲染归域服务（RoomPreviewRenderer）
     *
     * @return 按 last_msg_time 倒序的会话概要（含本人在会话的水位线与预览真源中间态）
     */
    List<RoomListItemVO> listRoomItems(String userId, int offset, int limit);

    /** 会话历史分页（uk_room_seq 主路径）：seq &lt; beforeSeq 倒序取 limit 条；beforeSeq 为 NULL 取最新一页 */
    List<MessageEntity> listMessagesBefore(String roomId, Long beforeSeq, int limit);

    /**
     * 增量补拉（timeline.uk_user_delivery 主路径）：delivery_seq &gt; deliverySeqCursor 升序取 limit 条，
     * 按 msg_id 集回查 message 取消息全量。timeline 永不删除，游标永远有效。
     * <p>
     * 返回的 nextCursor 取「扫描边界」（本页最后一行的 delivery_seq），与拼装成功几条无关：
     * timeline 行在而 message 行缺失（漂移）只少返回一条，游标照样越过它
     */
    TimelinePageVO listTimelineAfter(String userId, long deliverySeqCursor, int limit);

    // ===== room_member（会话维度名册：「这个会话有谁」） =====

    /** 多值 INSERT 名册扇出（1v1 恒两行，join_seq=0 入群水位）；与两行 user_room 同事务成对写入 */
    void insertRoomMembers(List<RoomMemberEntity> roomMemberEntityList);

    /** 名册枚举（成员扇出/对家回查/成员页的成员集来源，room_id 片键单分片查询面） */
    List<RoomMemberEntity> listRoomMembers(String roomId);

    long countRoomMembers(String roomId);

    // ===== user_room（用户维度会话状态） =====

    void insertUserRooms(List<UserRoomEntity> userRoomEntityList);

    /** 本人用户行点查（成员资格判据：行不存在 = 非成员） */
    UserRoomEntity findUserRoom(String roomId, String userId);

    /** 成员页两段查之②：名册给定成员集的用户行批量回查（水位线拼装，SQL 层零 JOIN） */
    List<UserRoomEntity> listUserRooms(String roomId, List<String> userIdList);

    /**
     * 已读水位线只前进不回退（read_seq &lt; 目标才更新，幂等）。
     *
     * @return 是否实际推进（0 = 水位线已不低于目标，调用方据此决定是否投递 READ 事件防噪音）
     */
    boolean updateUserReadSeq(String roomId, String userId, long readSeq);

    /** 新消息扇出时按给定成员集清空隐藏位（删除的会话弹回，微信行为；逐用户执行；成员集由调用方给定） */
    void clearHidden(String roomId, List<String> memberIdList);

    /** 用户侧隐藏会话（置 hidden_at，时刻取数据库时钟）；新消息 clearHidden 弹回 */
    void hideRoom(String roomId, String userId);

    // ===== message（内容中心） =====

    void insertMessage(MessageEntity messageEntity);

    MessageEntity findMessage(String roomId, Long seq);

    /** 发送幂等回查：撞 uk_client_msg 后按幂等键取回原消息，收敛同一条 */
    MessageEntity findMessageByClientMsgId(String roomId, String clientMsgId);

    /**
     * 撤回三件套：status=RECALLED + recalled_at + recalled_by，占 seq 不回退。
     * 条件 UPDATE（status=NORMAL 才生效）：用户撤回与强制撤回并发先到先得
     *
     * @return 是否实际撤回（0 = 已被撤回，调用方据此跳过 RECALL 事件）
     */
    boolean recallMessage(String roomId, Long seq, RecallByVO recalledBy);

    /** 举报回查：按消息全局主键取消息（MESSAGE 举报定位 / 强制撤回入口） */
    MessageEntity findMessageById(String messageId);

    // ===== timeline（不可变瘦收件箱） =====

    /** 多值 INSERT 扇出：一条消息 N 个成员 N 行，只插不改 */
    void insertTimelines(List<TimelineEntity> timelineEntityList);

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
    void savePushMessage(ChatMessagePushEvent chatMessagePushEvent);

    /** 水位线推进事务内写 READ 推送事件（outbox），仅水位线实际前进时调用（幂等无噪音） */
    void saveReadMessage(ChatReadPushEvent chatReadPushEvent);

    /** 撤回事务内写 RECALL 轻事件（outbox）：载荷只带 roomId+seq 定位，内容终态以拉取为准 */
    void saveRecallMessage(ChatRecallPushEvent chatRecallPushEvent);

    /**
     * WS 实时推送 MSG 帧（技术出站能力走仓储，同 outbox 待遇）：实现组装帧并委托 infrastructure/ws 连接组件。
     * 尽力而为：不在线/失败静默放弃，漏推由客户端判重 + timeline 补拉兜底
     */
    void pushMessage(String userId, ChatMessagePushEvent chatMessagePushEvent);

    /** WS 实时推送 READ 帧（已读水位事件）：同上尽力而为，漏推由客户端判重 + timeline 补拉兜底 */
    void pushRead(String userId, ChatReadPushEvent chatReadPushEvent);

    /** WS 实时推送 RECALL 帧（撤回轻事件，只带定位）：同上尽力而为，漏推接受退化（内容终态以拉取为准） */
    void pushRecall(String userId, ChatRecallPushEvent chatRecallPushEvent);
}
