package com.betterschool.domain.post.model.valobj;

import lombok.Data;

/**
 * 帖子域内的用户概要（跨域只读 port 的返回物）：user 域实体不出 infrastructure，
 * 只带 community 用得上的昵称与两个隐私开关原始值
 */
@Data
public class PostUserVO {

    private String userId;

    private String nickname;

    /** 用户资料「公开我的赞过」开关原始值；null 视为公开（读不到资料的口径） */
    private Boolean showLiked;

    /** 用户资料「公开我的评论」开关原始值；null 视为公开（读不到资料的口径） */
    private Boolean showComments;
}
