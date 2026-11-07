package com.betterschool.domain.chat.service.query;

import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.valobj.RoomMemberVO;
import com.betterschool.domain.chat.model.valobj.RoomListItemVO;
import com.betterschool.domain.chat.model.valobj.TimelineItemVO;

import java.util.List;

/**
 * chat 读路径查询（会话列表/历史/补拉/成员）：推是加速拉是真相，离线期间的一切靠这里
 */
public interface IChatQueryService {

    /**
     * 会话列表：排除 hidden_at 非空，按 last_msg_at 倒序分页；
     * 拼对方 userId+昵称+头像；unread = room.last_seq − 水位线（服务端拼装，不落副本）
     */
    List<RoomListItemVO> listRooms(String userId, int page, int pageSize);

    /** 当前会话数（列表分页 total，排除隐藏口径同 {@link #listRooms}） */
    long countRooms(String userId);

    /**
     * 历史消息：seq 倒序分页（上滑加载更早），页内按 seq 升序返回；
     * 已撤回消息脱敏「已撤回」空壳（content/ref 不出服务端）
     */
    List<MessageEntity> listMessages(String roomId, String userId, Long beforeSeq, Integer limit);

    /**
     * 增量补拉：delivery_seq 升序取条，返回条数 &lt; limit 即追平；afterDeliverySeq=0 = 全量
     */
    List<TimelineItemVO> pullTimeline(String userId, Long afterDeliverySeq, Integer limit);

    /**
     * 成员列表：含 last_read_seq（详情页已读头像推导 + 成员页同源）；
     * 非成员拒绝
     */
    List<RoomMemberVO> listMembers(String roomId, String userId);
}
