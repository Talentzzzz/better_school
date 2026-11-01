package com.betterschool.marketing.domain.common.model.valobj.marketing;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户基本信息（域内视图）：跨服务经 IUserQueryPort 取回。
 * 域层不认识 bs-api 的 UserBasicDTO，翻译在 infrastructure 完成。
 */
@Data
public class UserBasicVO {

    private String userId;
    private String nickname;
    private String avatar;
    /** 注册时间（探活接口透出给前端） */
    private LocalDateTime createdAt;
}
