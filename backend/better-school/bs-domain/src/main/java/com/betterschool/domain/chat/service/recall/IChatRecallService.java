package com.betterschool.domain.chat.service.recall;

/**
 * 消息撤回：发送者 2 分钟窗口内自行撤回。
 * 撤回占 seq 不回退、不动水位线计入未读；content 库内保留，读路径脱敏空壳
 */
public interface IChatRecallService {

    /** 发送者撤回自己的消息：仅本人，2 分钟窗口；已撤回幂等静默 */
    void recall(String roomId, Long seq, String userId);
}
