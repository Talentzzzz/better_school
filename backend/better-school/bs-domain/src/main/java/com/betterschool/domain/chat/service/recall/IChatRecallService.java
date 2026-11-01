package com.betterschool.domain.chat.service.recall;

/**
 * 消息撤回：用户侧 2 分钟窗口自行撤回 + 系统侧强制撤回（举报处理，无窗口限制）。
 * 撤回占 seq 不回退、不动水位线计入未读；content 库内保留，读路径脱敏空壳
 */
public interface IChatRecallService {

    /** 发送者撤回自己的消息：仅本人，2 分钟窗口；已撤回幂等静默 */
    void recall(String roomId, Long seq, String userId);

    /**
     * 强制撤回（举报核实处理）：recalled_by=AUDITOR，无窗口限制；已撤回（含用户自行撤回）幂等跳过只落处理结论
     */
    void forceRecallByAuditor(String messageId);
}
