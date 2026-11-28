package com.betterschool.domain.user.model.entity;

import com.betterschool.domain.user.model.valobj.GenderEnum;
import com.betterschool.domain.user.model.valobj.UserRoleEnum;
import com.betterschool.domain.user.model.valobj.UserStatusEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserEntity {

    private String userId;
    private String phone;
    private String nickname;
    private String avatar;
    /** 个性签名（可空＝不展示） */
    private String signature;
    /** 性别（可空＝不展示） */
    private GenderEnum gender;
    /** 隐私开关：公开我的赞过（仅他视生效） */
    private Boolean showLiked;
    /** 隐私开关：公开我的评论（仅他视生效） */
    private Boolean showComments;
    private UserRoleEnum role;
    private UserStatusEnum status;
    private Integer creditScore;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;

    public static UserEntity create(String phone, String nickname) {
        UserEntity userEntity = new UserEntity();
        userEntity.setPhone(phone);
        userEntity.setNickname(nickname);
        userEntity.setRole(UserRoleEnum.USER);
        userEntity.setStatus(UserStatusEnum.NORMAL);
        userEntity.setCreditScore(100);
        return userEntity;
    }
}
