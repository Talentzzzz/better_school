package com.betterschool.types.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 帖子点赞事实事件（topic {@code bs-post-liked}）：单体消费后落互动通知。
 * 只带「谁赞了谁的帖子」——通知的类型、范围、文案由消费方决定，生产方不持有通知词汇；
 * 作者随消息带上（消费方决策必需），消费端不反查帖子。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostLikedMessage {

    private String postId;
    /** 点赞人（通知 senderId） */
    private String likerUserId;
    /** 帖主（通知收件人）；与点赞人相同即自赞，生产端不发 */
    private String authorId;
}
