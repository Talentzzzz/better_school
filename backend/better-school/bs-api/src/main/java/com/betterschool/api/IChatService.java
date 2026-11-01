package com.betterschool.api;

import com.betterschool.api.dto.ChatMembersResponseDTO;
import com.betterschool.api.dto.ChatMessageResponseDTO;
import com.betterschool.api.dto.ChatRoomListResponseDTO;
import com.betterschool.api.dto.ChatRoomResponseDTO;
import com.betterschool.api.dto.CreateDirectRoomRequestDTO;
import com.betterschool.api.dto.SendMessageRequestDTO;
import com.betterschool.api.dto.WsTicketResponseDTO;
import com.betterschool.api.response.Response;

import java.util.List;

/**
 * chat 会话服务契约（票 02 发送链路 + 票 03 读路径）：HTTP 层由 Controller 实现，后续微服务化时由 Dubbo Provider 实现
 */
public interface IChatService {

    /** 建/取 1v1 会话：direct_room_pair.uk_pair 幂等返回既有 room */
    Response<ChatRoomResponseDTO> createDirectRoom(CreateDirectRoomRequestDTO request);

    /** 发送消息：200 返回完整消息体（含 roomId/seq），重复提交同 clientMsgId 收敛同一条 */
    Response<ChatMessageResponseDTO> sendMessage(String roomId, SendMessageRequestDTO request);

    /** 会话列表：排除隐藏，last_msg_at 倒序分页，拼对方资料，unread O(1) */
    Response<ChatRoomListResponseDTO> listRooms(int page, int pageSize);

    /** 历史消息：beforeSeq 倒序分页（页内 seq 升序），已撤回脱敏「已撤回」空壳 */
    Response<List<ChatMessageResponseDTO>> listMessages(String roomId, Long beforeSeq, Integer limit);

    /** 增量补拉：afterDeliverySeq 游标（0=全量），默认 200 硬顶 500，返回消息全量+deliverySeq */
    Response<List<ChatMessageResponseDTO>> pullTimeline(Long afterDeliverySeq, Integer limit);

    /** 打开会话推水位线到 room.last_seq：幂等，仅水位线前进时 UPDATE+投递 READ 事件；返回推进后水位线 */
    Response<Long> markRead(String roomId);

    /** 撤回消息：发送者本人 + 2 分钟窗口，占 seq 不回退、水位线不动；成功推 RECALL 轻事件 */
    Response<Void> recallMessage(String roomId, Long seq);

    /** 会话隐藏：置 hidden_at，列表立即消失，新消息自动弹回 */
    Response<Void> hideRoom(String roomId);

    /** 成员列表：含 last_read_seq 与成员数（恒两行）；非成员拒绝 */
    Response<ChatMembersResponseDTO> listMembers(String roomId);

    /** WS 连接票据：60s 一次性，握手原子取删 */
    Response<WsTicketResponseDTO> createWsTicket();
}
