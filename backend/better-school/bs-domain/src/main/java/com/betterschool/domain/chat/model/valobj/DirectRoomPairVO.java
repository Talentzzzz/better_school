package com.betterschool.domain.chat.model.valobj;

import lombok.Data;

/**
 * 1v1 会话配对键（direct_room_pair.user_min/user_max）：user_id 是 UUID 字符串后无数值序，
 * 按字符串字典序取规范序（仅用于撞键收敛，语义与数值 min/max 等价）。
 * 规范序计算只此一处：ChatSendServiceImpl 建会话前查重与 ChatRepository 写配对行共用，避免两处口径漂移
 */
@Data
public class DirectRoomPairVO {

    private String userMin;
    private String userMax;

    public static DirectRoomPairVO canonicalOf(String a, String b) {
        DirectRoomPairVO pair = new DirectRoomPairVO();
        if (a.compareTo(b) < 0) {
            pair.setUserMin(a);
            pair.setUserMax(b);
        } else {
            pair.setUserMin(b);
            pair.setUserMax(a);
        }
        return pair;
    }
}
