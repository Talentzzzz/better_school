package com.betterschool.infrastructure.dao.user.po;

import lombok.Data;

/**
 * 用户窄投影行（会话列表/消息发送者等纯展示场景）：只取昵称头像三列。
 * 与 selectByIds 返回的完整 User 行区分——展示路径不拉 phone 等敏感列，
 * 意图落在 SQL 层而不是靠调用方丢掉多余字段。
 */
@Data
public class UserBrief {

    private String userId;
    private String nickname;
    private String avatar;
}
