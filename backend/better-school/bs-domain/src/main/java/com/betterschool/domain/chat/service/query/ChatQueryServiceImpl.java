package com.betterschool.domain.chat.service.query;

import com.betterschool.domain.chat.adapter.port.IUserQueryPort;
import com.betterschool.domain.chat.adapter.repository.IChatRepository;
import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.entity.RoomMemberEntity;
import com.betterschool.domain.chat.model.entity.UserRoomEntity;
import com.betterschool.domain.chat.model.valobj.ChatUserVO;
import com.betterschool.domain.chat.model.valobj.RoomMemberVO;
import com.betterschool.domain.chat.model.valobj.RoomPeerRoleVO;
import com.betterschool.domain.chat.model.valobj.RoomListItemVO;
import com.betterschool.domain.chat.model.valobj.TimelineItemVO;
import com.betterschool.domain.chat.model.valobj.TimelinePageVO;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * chat 读路径查询实现：脱敏与分页边界在域内收敛；用户资料经 chat 自有 port（IUserQueryPort）只读他域
 * ——跨域只读一律走消费方域自有 port（CLAUDE.md §1），不是「例外」。
 */
@Service
@RequiredArgsConstructor
public class ChatQueryServiceImpl implements IChatQueryService {

    /** 历史分页默认页大小与硬顶（spec §4：默认 50） */
    public static final int DEFAULT_HISTORY_LIMIT = 50;

    /** 补拉默认页大小与硬顶（spec §2：默认 200 硬顶 500） */
    public static final int DEFAULT_TIMELINE_LIMIT = 200;
    public static final int MAX_TIMELINE_LIMIT = 500;
    public static final int MAX_HISTORY_LIMIT = 500;

    private final IChatRepository chatRepository;
    private final IUserQueryPort userQueryPort;

    @Override
    public List<RoomListItemVO> listRooms(String userId, int page, int pageSize) {
        List<RoomListItemVO> rooms = chatRepository.listRoomItems(userId, (page - 1) * pageSize, pageSize);
        // 1v1 拼对方资料（page ≤ 100，批量反查一次）；对家 user_id 由名册回查（selectPeerMembers）直出
        List<String> peerIds = rooms.stream()
                .map(RoomListItemVO::getPeerUserId)
                .toList();
        if (peerIds.isEmpty()) {
            return rooms;
        }
        Map<String, ChatUserVO> users = userQueryPort.listByIds(peerIds).stream()
                .collect(Collectors.toMap(ChatUserVO::getUserId, Function.identity()));
        for (RoomListItemVO room : rooms) {
            String peerId = room.getPeerUserId();
            ChatUserVO peer = users.get(peerId);
            room.setPeerNickname(peer == null ? null : peer.getNickname());
            room.setPeerAvatar(peer == null ? null : peer.getAvatar());
            room.setPeerRole(peerRoleOf(room, userId));
            // 预览读时现算（票 02 预览去副本）：渲染规则单点收敛在 RoomPreviewRenderer，message 是唯一真相
            RoomPreviewRenderer.renderInto(room, room.getLastPreview());
        }
        return rooms;
    }

    /**
     * 对家角色（市集消息页的角色小标签；来源为空 = 非市集入口建的会话，不出标签）。
     * 闲置的入口方向单一，谁点「私信联系」谁是哪一方是定的（商品详情「联系卖家」= 买家发起），
     * 故按「本人是不是会话创建人」推——同一会话两侧各自看到自己的对家；
     * 跑腿单两侧都能发起（发布方联系跑手 / 跑手联系发布方），来源列定不了方向，按票面口径对家恒为跑手
     */
    private RoomPeerRoleVO peerRoleOf(RoomListItemVO room, String userId) {
        if (room.getSource() == null) {
            return null;
        }
        boolean amCreator = userId.equals(room.getCreatorId());
        return switch (room.getSource()) {
            case ERRAND -> RoomPeerRoleVO.TAKER;
            case PRODUCT -> amCreator ? RoomPeerRoleVO.SELLER : RoomPeerRoleVO.BUYER;
        };
    }

    @Override
    public List<MessageEntity> listMessages(String roomId, String userId, Long beforeSeq, Integer limit) {
        requireMembership(roomId, userId);
        int effectiveLimit = clamp(limit, DEFAULT_HISTORY_LIMIT, MAX_HISTORY_LIMIT);
        List<MessageEntity> messages = new ArrayList<>(
                chatRepository.listMessagesBefore(roomId, beforeSeq, effectiveLimit));
        // 游标倒退取页，页内按 seq 升序交付（前端整块前插）
        Collections.reverse(messages);
        // 撤回脱敏「已撤回」空壳：content 库内保留取证，读路径一律不出服务端
        for (MessageEntity messageEntity : messages) {
            if (messageEntity.isRecalled()) {
                messageEntity.setContent(null);
                messageEntity.setRefType(null);
                messageEntity.setRefId(null);
            }
        }
        return messages;
    }

    @Override
    public TimelinePageVO pullTimeline(String userId, Long deliverySeqCursor, Integer limit) {
        // 游标 = 已拉到的投递序号（不含），空值按 0 处理 = 全量
        long cursor = deliverySeqCursor == null ? 0L : deliverySeqCursor;
        int effectiveLimit = clamp(limit, DEFAULT_TIMELINE_LIMIT, MAX_TIMELINE_LIMIT);
        TimelinePageVO page = chatRepository.listTimelineAfter(userId, cursor, effectiveLimit);
        // 下发与读路径同口径：撤回消息脱敏空壳（spec §1），content 库内保留取证不出服务端
        for (TimelineItemVO item : page.getItems()) {
            if (item.getMessage().isRecalled()) {
                item.getMessage().setContent(null);
                item.getMessage().setRefType(null);
                item.getMessage().setRefId(null);
            }
        }
        return page;
    }

    @Override
    public List<RoomMemberVO> listMembers(String roomId, String userId) {
        requireMembership(roomId, userId);
        // 成员页两段查（SQL 层零 JOIN）：名册给定成员集（「这个会话有谁」只从名册取）→
        // user_room 行批量回查拼 per-user 已读水位线
        List<RoomMemberEntity> members = chatRepository.listRoomMembers(roomId);
        List<String> memberIds = members.stream().map(RoomMemberEntity::getUserId).toList();
        Map<String, UserRoomEntity> userRooms = chatRepository.listUserRooms(roomId, memberIds).stream()
                .collect(Collectors.toMap(UserRoomEntity::getUserId, Function.identity(), (a, b) -> a));
        Map<String, ChatUserVO> users = userQueryPort.listByIds(memberIds).stream()
                .collect(Collectors.toMap(ChatUserVO::getUserId, Function.identity()));
        List<RoomMemberVO> result = new ArrayList<>(members.size());
        for (RoomMemberEntity memberEntity : members) {
            RoomMemberVO vo = new RoomMemberVO();
            vo.setUserId(memberEntity.getUserId());
            UserRoomEntity userRoomEntity = userRooms.get(memberEntity.getUserId());
            vo.setReadSeq(userRoomEntity == null ? 0L : userRoomEntity.getReadSeq());
            ChatUserVO user = users.get(memberEntity.getUserId());
            vo.setNickname(user == null ? null : user.getNickname());
            vo.setAvatar(user == null ? null : user.getAvatar());
            result.add(vo);
        }
        return result;
    }

    // ===== 校验 =====

    private void requireMembership(String roomId, String userId) {
        if (chatRepository.findUserRoom(roomId, userId) == null) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "非会话成员");
        }
    }

    private int clamp(Integer limit, int defaultValue, int maxValue) {
        if (limit == null) {
            return defaultValue;
        }
        return Math.max(1, Math.min(limit, maxValue));
    }
}
