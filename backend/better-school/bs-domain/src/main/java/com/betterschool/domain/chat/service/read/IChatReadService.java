package com.betterschool.domain.chat.service.read;

/**
 * 会话成员状态写路径（水位线/隐藏）
 */
public interface IChatReadService {

    /**
     * 打开会话推水位线到 room.last_seq（微信「打开即已读」）：仅水位线实际前进才 UPDATE + 提交后投递
     * READ outbox 事件（幂等无噪音），重复打开/落后于水位线的目标不产生事件。
     *
     * @return 推进后的水位线值（未推进 = 原水位线）
     */
    long markRead(String roomId, String userId);

    /** 用户侧隐藏会话（列表立即消失，新消息自动弹回） */
    void hideRoom(String roomId, String userId);
}
