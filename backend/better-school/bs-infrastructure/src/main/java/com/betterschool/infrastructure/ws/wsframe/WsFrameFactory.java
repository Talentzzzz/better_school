package com.betterschool.infrastructure.ws.wsframe;

import com.betterschool.domain.chat.model.valobj.ChatMessagePushEvent;
import com.betterschool.domain.chat.model.valobj.ChatReadPushEvent;
import com.betterschool.domain.chat.model.valobj.ChatRecallPushEvent;
import com.betterschool.domain.notification.model.valobj.NotifyPushMessage;
import com.betterschool.types.ws.WsCloseCodes;

/**
 * WS 帧组装工厂：「域事件 → 线上帧」的唯一归属地（帧信封归 ws/wsframe 托管）。
 * 业务侧只传各自的推送事件对象，线上 JSON 契约 {type, payload?, ts} 的字段映射
 * （含 null→"" 归一化）全部收口在此——改线上协议只动这一个类 + 对应 Ws*Payload 契约类；
 * ts 统一由工厂盖当前时间，调用方不传。
 * payload 一律为显式契约类（禁 Map 拼装/域事件直传，防止域字段重构静默改变线上协议），
 * 各方法返回类型的泛型实参即钉死 type ↔ payload 配对
 */
public final class WsFrameFactory {

    private WsFrameFactory() {
    }

    /** MSG：payload=完整消息体（含 roomId/seq 判重键、发送者昵称头像，气泡渲染免二次查询），客户端按 (roomId, seq) 判重 */
    public static WsFrame<WsMsgPayload> msg(ChatMessagePushEvent event) {
        return new WsFrame<>(WsFrameType.MSG, new WsMsgPayload(
                event.getRoomId(),
                event.getSeq(),
                event.getMessageId(),
                event.getFromUserId(),
                event.getFromNickname(),
                event.getFromAvatar(),
                event.getMsgType(),
                event.getContent(),
                event.getRefType(),
                event.getRefId(),
                event.getClientMsgId(),
                event.getCreatedAt()),
                now());
    }

    /** READ：payload={roomId, userId(readerId), readSeq}，供前端就地更新成员水位 map */
    public static WsFrame<WsReadPayload> read(ChatReadPushEvent event) {
        return new WsFrame<>(WsFrameType.READ, new WsReadPayload(
                event.getRoomId(),
                event.getUserId(),
                event.getReadSeq()),
                now());
    }

    /** RECALL：轻事件只带定位 payload={roomId, seq}，内容终态以拉取为准 */
    public static WsFrame<WsRecallPayload> recall(ChatRecallPushEvent event) {
        return new WsFrame<>(WsFrameType.RECALL, new WsRecallPayload(
                event.getRoomId(),
                event.getSeq()),
                now());
    }

    /** NOTIF：scope/seq/type/title/content/refType/refId payload，与前端横幅渲染约定；refType/refId null→"" 归一化 */
    public static WsFrame<WsNotifPayload> notif(NotifyPushMessage message) {
        return new WsFrame<>(WsFrameType.NOTIF, new WsNotifPayload(
                message.getScope(),
                message.getSeq(),
                message.getType(),
                message.getTitle(),
                message.getContent(),
                emptyIfNull(message.getRefType()),
                emptyIfNull(message.getRefId())),
                now());
    }

    /** PONG：心跳应答，无 payload */
    public static WsFrame<Void> pong() {
        return new WsFrame<>(WsFrameType.PONG, null, now());
    }

    /** KICK：互踢通知，payload={reason}，随后 close(4001) 阻断自动重连 */
    public static WsFrame<WsKickPayload> kick() {
        return new WsFrame<>(WsFrameType.KICK, new WsKickPayload(WsCloseCodes.KICK_REASON_SESSION_TAKEN_OVER), now());
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
