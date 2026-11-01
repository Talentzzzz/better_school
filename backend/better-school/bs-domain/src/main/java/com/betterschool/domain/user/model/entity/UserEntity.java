package com.betterschool.domain.user.model.entity;

import com.betterschool.domain.user.model.valobj.GenderVO;
import com.betterschool.domain.user.model.valobj.UserRoleVO;
import com.betterschool.domain.user.model.valobj.UserStatusVO;
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
    private GenderVO gender;
    /** 隐私开关：公开我的赞过（仅他视生效） */
    private Boolean showLiked;
    /** 隐私开关：公开我的评论（仅他视生效） */
    private Boolean showComments;
    private UserRoleVO role;
    private UserStatusVO status;
    private Integer creditScore;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;

    public static UserEntity create(String phone, String nickname) {
        UserEntity userEntity = new UserEntity();
        userEntity.setPhone(phone);
        userEntity.setNickname(nickname);
        userEntity.setRole(UserRoleVO.USER);
        userEntity.setStatus(UserStatusVO.NORMAL);
        userEntity.setCreditScore(100);
        userEntity.setCreatedAt(LocalDateTime.now());
        return userEntity;
    }
}
