package com.betterschool.trigger.http;

import com.betterschool.api.IChatService;
import com.betterschool.api.dto.ChatMembersResponseDTO;
import com.betterschool.api.dto.ChatMessageResponseDTO;
import com.betterschool.api.dto.ChatRoomListResponseDTO;
import com.betterschool.api.dto.ChatRoomResponseDTO;
import com.betterschool.api.dto.ChatRoomListItemResponseDTO;
import com.betterschool.api.dto.CreateDirectRoomRequestDTO;
import com.betterschool.api.dto.SendMessageRequestDTO;
import com.betterschool.api.dto.WsTicketResponseDTO;
import com.betterschool.api.response.Response;
import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.entity.RoomEntity;
import com.betterschool.domain.chat.model.valobj.CardRefTypeVO;
import com.betterschool.domain.chat.model.valobj.MsgTypeVO;
import com.betterschool.domain.chat.model.valobj.RoomMemberVO;
import com.betterschool.domain.chat.model.valobj.RoomSourceVO;
import com.betterschool.domain.chat.model.valobj.RoomListItemVO;
import com.betterschool.domain.chat.model.valobj.TimelineItemVO;
import com.betterschool.domain.chat.adapter.port.IWsTicketPort;
import com.betterschool.domain.chat.service.query.IChatQueryService;
import com.betterschool.domain.chat.service.recall.IChatRecallService;
import com.betterschool.domain.chat.service.read.IChatReadService;
import com.betterschool.domain.chat.service.send.IChatSendService;
import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.trigger.http.security.SecurityUtils;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * chat 会话 HTTP 入口（票 02 发送链路 + 票 03 读路径）
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController implements IChatService {

    /** 列表分页默认值与硬顶 */
    private static final int MAX_PAGE_SIZE = 200;

    private final IChatSendService chatSendService;
    private final IChatQueryService chatQueryService;
    private final IChatReadService chatReadService;
    private final IChatRecallService chatRecallService;
    private final IWsTicketPort wsTicketPort;
    private final IUserRepository userRepository;

    @Override
    @PostMapping("/rooms/direct")
    public Response<ChatRoomResponseDTO> createDirectRoom(@Valid @RequestBody CreateDirectRoomRequestDTO request) {
        RoomEntity room = chatSendService.createDirectRoom(SecurityUtils.currentUserId(),
                request.getTargetUserId(), parseRoomSource(request.getSource()));
        ChatRoomResponseDTO dto = new ChatRoomResponseDTO();
        dto.setRoomId(room.getRoomId());
        return Response.ok(dto);
    }

    @Override
    @PostMapping("/rooms/{roomId}/messages")
    public Response<ChatMessageResponseDTO> sendMessage(@PathVariable String roomId,
                                                        @Valid @RequestBody SendMessageRequestDTO request) {
        MessageEntity message = chatSendService.sendMessage(roomId, SecurityUtils.currentUserId(),
                request.getClientMsgId(), parseMsgType(request.getMsgType()), request.getContent(),
                parseRefType(request.getRefType()), request.getRefId());
        ChatMessageResponseDTO dto = toMessageResponseDTO(message, null);
        fillSenderInfo(dto, message.getFromUserId());
        return Response.ok(dto);
    }

    @Override
    @GetMapping("/rooms")
    public Response<ChatRoomListResponseDTO> listRooms(@RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "50") int pageSize) {
        int effectivePage = Math.max(1, page);
        int effectivePageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        List<RoomListItemVO> rooms = chatQueryService.listRooms(SecurityUtils.currentUserId(),
                effectivePage, effectivePageSize);
        ChatRoomListResponseDTO dto = new ChatRoomListResponseDTO();
        dto.setPage(effectivePage);
        dto.setPageSize(effectivePageSize);
        dto.setTotal(chatQueryService.countRooms(SecurityUtils.currentUserId()));
        dto.setList(rooms.stream().map(this::toRoomListItemResponseDTO).toList());
        return Response.ok(dto);
    }

    @Override
    @GetMapping("/rooms/{roomId}/messages")
    public Response<List<ChatMessageResponseDTO>> listMessages(@PathVariable String roomId,
                                                               @RequestParam(required = false) Long beforeSeq,
                                                               @RequestParam(required = false) Integer limit) {
        List<MessageEntity> messages = chatQueryService.listMessages(roomId, SecurityUtils.currentUserId(),
                beforeSeq, limit);
        List<ChatMessageResponseDTO> dtos = messages.stream()
                .map(message -> toMessageResponseDTO(message, null))
                .toList();
        return Response.ok(fillSenderInfo(dtos));
    }

    @Override
    @GetMapping("/timeline")
    public Response<List<ChatMessageResponseDTO>> pullTimeline(@RequestParam(required = false) Long afterDeliverySeq,
                                                               @RequestParam(required = false) Integer limit) {
        List<TimelineItemVO> items = chatQueryService.pullTimeline(SecurityUtils.currentUserId(),
                afterDeliverySeq, limit);
        List<ChatMessageResponseDTO> dtos = items.stream()
                .map(item -> toMessageResponseDTO(item.getMessage(), item.getDeliverySeq()))
                .toList();
        return Response.ok(fillSenderInfo(dtos));
    }

    @Override
    @PostMapping("/rooms/{roomId}/read")
    public Response<Long> markRead(@PathVariable String roomId) {
        return Response.ok(chatReadService.markRead(roomId, SecurityUtils.currentUserId()));
    }

    @Override
    @PostMapping("/rooms/{roomId}/messages/{seq}/recall")
    public Response<Void> recallMessage(@PathVariable String roomId, @PathVariable Long seq) {
        chatRecallService.recall(roomId, seq, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @PostMapping("/rooms/{roomId}/hide")
    public Response<Void> hideRoom(@PathVariable String roomId) {
        chatReadService.hideRoom(roomId, SecurityUtils.currentUserId());
        return Response.ok(null);
    }

    @Override
    @GetMapping("/rooms/{roomId}/members")
    public Response<ChatMembersResponseDTO> listMembers(@PathVariable String roomId) {
        List<RoomMemberVO> members = chatQueryService.listMembers(roomId, SecurityUtils.currentUserId());
        ChatMembersResponseDTO dto = new ChatMembersResponseDTO();
        dto.setMemberCount(members.size());
        dto.setMembers(members.stream().map(this::toMemberResponseDTO).toList());
        return Response.ok(dto);
    }

    @Override
    @PostMapping("/ws-ticket")
    public Response<WsTicketResponseDTO> createWsTicket() {
        WsTicketResponseDTO dto = new WsTicketResponseDTO();
        dto.setTicket(wsTicketPort.issue(SecurityUtils.currentUserId()));
        return Response.ok(dto);
    }

    // ===== 装配 =====

    private MsgTypeVO parseMsgType(String msgType) {
        try {
            return MsgTypeVO.valueOf(msgType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "消息类型不合法");
        }
    }

    /** 会话来源可省略（首页/用户主页入口）；写了就必须是市集两域之一 */
    private RoomSourceVO parseRoomSource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            return RoomSourceVO.valueOf(source);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "会话来源不合法");
        }
    }

    private CardRefTypeVO parseRefType(String refType) {
        if (refType == null) {
            return null;
        }
        try {
            return CardRefTypeVO.valueOf(refType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "卡片引用类型不合法");
        }
    }

    private ChatRoomListItemResponseDTO toRoomListItemResponseDTO(RoomListItemVO room) {
        ChatRoomListItemResponseDTO dto = new ChatRoomListItemResponseDTO();
        dto.setRoomId(room.getRoomId());
        dto.setPeerUserId(room.getPeerUserId());
        dto.setPeerNickname(room.getPeerNickname());
        dto.setPeerAvatar(room.getPeerAvatar());
        dto.setPeerRole(room.getPeerRole() == null ? null : room.getPeerRole().name());
        dto.setSource(room.getSource() == null ? null : room.getSource().name());
        dto.setUnread(room.getUnread());
        dto.setLastMsgType(room.getLastMsgType() == null ? null : room.getLastMsgType().name());
        dto.setLastMsgPreview(room.getLastMsgPreview());
        dto.setLastMsgAt(room.getLastMsgAt());
        return dto;
    }

    private ChatMembersResponseDTO.ChatMemberResponseDTO toMemberResponseDTO(RoomMemberVO member) {
        ChatMembersResponseDTO.ChatMemberResponseDTO dto = new ChatMembersResponseDTO.ChatMemberResponseDTO();
        dto.setUserId(member.getUserId());
        dto.setNickname(member.getNickname());
        dto.setAvatar(member.getAvatar());
        dto.setLastReadSeq(member.getLastReadSeq());
        return dto;
    }

    /** 发送者昵称头像补齐：单条按 id 查，列表批查一次；SYSTEM 消息无发送者 */
    private ChatMessageResponseDTO fillSenderInfo(ChatMessageResponseDTO dto, String fromUserId) {
        if (fromUserId == null) {
            return dto;
        }
        UserEntity sender = userRepository.findById(fromUserId);
        dto.setFromNickname(sender == null ? null : sender.getNickname());
        dto.setFromAvatar(sender == null ? null : sender.getAvatar());
        return dto;
    }

    private List<ChatMessageResponseDTO> fillSenderInfo(List<ChatMessageResponseDTO> dtos) {
        List<String> senderIds = dtos.stream()
                .map(ChatMessageResponseDTO::getFromUserId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (senderIds.isEmpty()) {
            return dtos;
        }
        Map<String, UserEntity> senders = userRepository.listByIds(senderIds).stream()
                .collect(Collectors.toMap(UserEntity::getUserId, Function.identity()));
        for (ChatMessageResponseDTO dto : dtos) {
            UserEntity sender = senders.get(dto.getFromUserId());
            dto.setFromNickname(sender == null ? null : sender.getNickname());
            dto.setFromAvatar(sender == null ? null : sender.getAvatar());
        }
        return dtos;
    }

    private ChatMessageResponseDTO toMessageResponseDTO(MessageEntity message, Long deliverySeq) {
        ChatMessageResponseDTO dto = new ChatMessageResponseDTO();
        dto.setRoomId(message.getRoomId());
        dto.setMessageId(message.getMessageId());
        dto.setSeq(message.getSeq());
        dto.setFromUserId(message.getFromUserId());
        dto.setMsgType(message.getMsgType().name());
        dto.setStatus(message.getStatus().name());
        dto.setContent(message.getContent());
        dto.setRefType(message.getRefType() == null ? null : message.getRefType().name());
        dto.setRefId(message.getRefId());
        dto.setClientMsgId(message.getClientMsgId());
        dto.setDeliverySeq(deliverySeq);
        dto.setCreatedAt(message.getCreatedAt());
        return dto;
    }
}
