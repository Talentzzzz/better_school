package com.betterschool.domain.chat.adapter.port;

import com.betterschool.domain.chat.model.valobj.ChatWsFrame;

/**
 * WS 推送端口（按 userId 投递一帧）：投递机器（连接注册表/心跳判死/帧写出器/
 * 多实例路由/实例订阅）整体封装在 infra 的 WS 推送适配器后，业务侧对「怎么送」零感知。
 * <p>尽力而为：不在线/推送失败即放弃，不重试——漏推合法，
 * 客户端按 (roomId, seq) 判重 + timeline 增量补拉兜底
 */
public interface IWsPushPort {

    /**
     * 向指定用户投递一帧：目标用户本机在线即推，多实例形态下经路由表/定向频道转投目标实例；
     * 不在线或投递失败静默放弃（漏推由补拉兜底）
     */
    void deliver(String userId, ChatWsFrame frame);
}
