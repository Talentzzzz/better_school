package com.betterschool.domain.chat.service.query;

import com.betterschool.domain.chat.adapter.repository.IChatRepository;
import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.entity.RoomMemberEntity;
import com.betterschool.domain.chat.model.entity.UserRoomEntity;
import com.betterschool.domain.chat.model.valobj.RoomMemberVO;
import com.betterschool.domain.chat.model.valobj.RoomPeerRoleEnum;
import com.betterschool.domain.chat.model.valobj.RoomListItemVO;
import com.betterschool.domain.chat.model.valobj.TimelineItemVO;
import com.betterschool.domain.chat.model.valobj.TimelinePageVO;
import com.betterschool.types.enums.ResponseCodeEnum;
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
 * chat 读路径查询实现：脱敏与分页边界在域内收敛；用户资料由 case 层取回后回填，
 * 本域不读他域，也不持有任何跨域 port。
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

    @Override
    public List<RoomListItemVO> listRooms(String userId, int page, int pageSize) {
        List<RoomListItemVO> rooms = chatRepository.listRoomItems(userId, (page - 1) * pageSize, pageSize);
        for (RoomListItemVO roomListItemVO : rooms) {
            roomListItemVO.setPeerRole(peerRoleOf(roomListItemVO, userId));
            // 预览读时现算（票 02 预览去副本）：渲染规则单点收敛在 RoomPreviewRenderer，message 是唯一真相
            RoomPreviewRenderer.renderInto(roomListItemVO, roomListItemVO.getLastPreview());
        }
        return rooms;
    }

    /**
     * 对家角色（市集消息页的角色小标签；来源为空 = 非市集入口建的会话，不出标签）。
     * 闲置的入口方向单一，谁点「私信联系」谁是哪一方是定的（商品详情「联系卖家」= 买家发起），
     * 故按「本人是不是会话创建人」推——同一会话两侧各自看到自己的对家
     */
    private RoomPeerRoleEnum peerRoleOf(RoomListItemVO roomListItemVO, String userId) {
        if (roomListItemVO.getSource() == null) {
            return null;
        }
        boolean amCreator = userId.equals(roomListItemVO.getCreatorId());
        return switch (roomListItemVO.getSource()) {
            case PRODUCT -> amCreator ? RoomPeerRoleEnum.SELLER : RoomPeerRoleEnum.BUYER;
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
        TimelinePageVO timelinePageVO = chatRepository.listTimelineAfter(userId, cursor, effectiveLimit);
        // 下发与读路径同口径：撤回消息脱敏空壳（spec §1），content 库内保留取证不出服务端
        for (TimelineItemVO timelineItemVO : timelinePageVO.getItems()) {
            if (timelineItemVO.getMessage().isRecalled()) {
                timelineItemVO.getMessage().setContent(null);
                timelineItemVO.getMessage().setRefType(null);
                timelineItemVO.getMessage().setRefId(null);
            }
        }
        return timelinePageVO;
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
        List<RoomMemberVO> result = new ArrayList<>(members.size());
        for (RoomMemberEntity roomMemberEntity : members) {
            RoomMemberVO roomMemberVO = new RoomMemberVO();
            roomMemberVO.setUserId(roomMemberEntity.getUserId());
            UserRoomEntity userRoomEntity = userRooms.get(roomMemberEntity.getUserId());
            roomMemberVO.setReadSeq(userRoomEntity == null ? 0L : userRoomEntity.getReadSeq());
            result.add(roomMemberVO);
        }
        return result;
    }

    // ===== 校验 =====

    private void requireMembership(String roomId, String userId) {
        if (chatRepository.findUserRoom(roomId, userId) == null) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "非会话成员");
        }
    }

    private int clamp(Integer limit, int defaultValue, int maxValue) {
        if (limit == null) {
            return defaultValue;
        }
        return Math.max(1, Math.min(limit, maxValue));
    }
}
