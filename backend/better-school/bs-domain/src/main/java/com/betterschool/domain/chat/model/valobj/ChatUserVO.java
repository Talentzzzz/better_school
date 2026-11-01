package com.betterschool.domain.chat.model.valobj;

import lombok.Data;

/**
 * 用户资料的 chat 域内投影（跨域只读端口的返回值）：只带会话展示要用的三列，
 * 不把 user 域的实体带进 chat 域
 */
@Data
public class ChatUserVO {

    private String userId;
    private String nickname;
    private String avatar;
}
