package com.betterschool.domain.chat.service.send;

import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.entity.RoomEntity;
import com.betterschool.domain.chat.model.valobj.ChatUserVO;
import com.betterschool.domain.chat.model.valobj.RoomSourceEnum;

/**
 * chat 发送用例（落地票 02）：建/取 1v1 会话 + 消息发送主链路。
 * 发送事务内完成：撞 uk_client_msg 幂等收敛 → room 取 seq → message INSERT → user_seq 取号
 * → timeline 扇出（清 hidden_at 弹回）→ room_member 列表副本（水位 + 预览三件套）→ outbox MSG 推送事件
 */
public interface IChatSendService {

    /**
     * 建/取 1v1 会话：user_pair_room.uk_pair 幂等，同一对人重复建返回既有 room（「联系 TA」入口）。
     *
     * @param source 会话来源（市集三入口各带一个；首页/用户主页私信入口传 null）；
     *               仅在**首次建会话**时写入，既有会话原样返回（首来源钉死）
     */
    RoomEntity createDirectRoom(String userId, String targetUserId, RoomSourceEnum roomSourceEnum);

    /**
     * 发送消息（用户侧四类型中 SYSTEM 仅系统侧不可经此发送）。
     *
     * @param messageEntity 待发消息草稿：roomId/fromUserId/clientMsgId/msgType/content/refType/refId 由调用方装配；
     *                messageId/seq/status/createdAt 由服务内事务补齐
     * @param sender        发送人昵称头像（case 层跨域取回后传入，domain 不读 user 域）
     * @return 落库后的完整消息体（含 roomId/seq/messageId/createdAt）；重复提交收敛返回原消息
     */
    MessageEntity sendMessage(MessageEntity messageEntity, ChatUserVO chatUserVO);
}
