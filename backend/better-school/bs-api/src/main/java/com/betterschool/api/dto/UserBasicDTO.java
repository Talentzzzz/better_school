package com.betterschool.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户基本信息（跨服务同步边的最小读取面）：营销服务经 Dubbo 取回后应用层拼装展示。
 * 不含手机号/信用分/隐私开关——跨服务只取「本域必需」，不外溢用户域的其他事实。
 */
@Data
public class UserBasicDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String userId;
    private String nickname;
    private String avatar;
    /** 注册时间 */
    private LocalDateTime createdAt;
}
