package com.betterschool.domain.chat.service.push;

import com.betterschool.domain.chat.model.valobj.ChatMessagePushEvent;
import com.betterschool.domain.chat.model.valobj.ChatReadPushEvent;
import com.betterschool.domain.chat.model.valobj.ChatRecallPushEvent;

/**
 * WS 实时推送（写事务提交后的直接投递）：按会话成员逐个经本域仓储 pushXxx 投递，
 * 只传本域推送事件、不碰帧信封（线上协议归 infrastructure/ws 托管，仓储实现组帧），
 * 仅在线成员推送，不在线直接跳过（漏推合法，timeline 补拉兜底）。
 * <p>
 * 成员扇出决策的唯一归属地：经 chat 仓储解析会话成员，MSG/READ 排除触发者
 * （MSG 发送方以 HTTP 200 为准、READ reader 本人不需要回执），RECALL 推全员（含发送者）。
 * 推送是加速路径：本服务不做事务、不做重试，投递失败/不在线由仓储出站静默放弃
 */
public interface IChatPushService {

    /** MSG：payload=完整消息体（含发送者昵称头像），客户端按 (roomId, seq) 判重 */
    void pushMessage(ChatMessagePushEvent chatMessagePushEvent);

    /** READ：payload={userId(readerId), readSeq}，推给 reader 之外的其他在线成员 */
    void pushRead(ChatReadPushEvent chatReadPushEvent);

    /** RECALL：轻事件只带定位，推给会话全部在线成员 */
    void pushRecall(ChatRecallPushEvent chatRecallPushEvent);
}
