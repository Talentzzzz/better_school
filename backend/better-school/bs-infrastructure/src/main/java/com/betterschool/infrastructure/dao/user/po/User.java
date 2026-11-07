package com.betterschool.infrastructure.dao.user.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class User {

    private Long id;
    /** 业务 ID（对外唯一标识；与物理自增同值，插入后同事务回写） */
    private String userId;
    private String phone;
    private String nickname;
    private String avatar;
    /** 个性签名（可空；≤40 字） */
    private String signature;
    /** 性别 MALE/FEMALE（可空＝不展示） */
    private String gender;
    /** 隐私开关：公开我的赞过（1 公开 / 0 仅自己） */
    private Boolean showLiked;
    /** 隐私开关：公开我的评论（1 公开 / 0 仅自己） */
    private Boolean showComments;
    private String role;
    private String state;
    private Integer creditScore;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
