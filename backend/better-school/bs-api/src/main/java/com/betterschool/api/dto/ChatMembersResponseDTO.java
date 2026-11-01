package com.betterschool.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 成员列表（详情页已读头像推导 + 成员页同源）：1v1 会话恒两行
 */
@Data
public class ChatMembersResponseDTO {

    /** 当前成员数 */
    private long memberCount;

    private List<ChatMemberResponseDTO> members;

    @Data
    public static class ChatMemberResponseDTO {

        private String userId;

        private String nickname;

        private String avatar;

        /** 已读水位线（seq ≤ 水位线 = 已读，纯前端推导） */
        private long lastReadSeq;
    }
}
