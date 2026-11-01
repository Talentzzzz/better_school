package com.betterschool.domain.chat.model.valobj;

import lombok.Data;

/**
 * 成员列表项（成员页/详情页已读头像同源）：水位线纯前端推导已读（seq ≤ 水位线）
 */
@Data
public class RoomMemberVO {

    private String userId;

    private String nickname;

    private String avatar;

    private long lastReadSeq;
}
