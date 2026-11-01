package com.betterschool.infrastructure.dao.post.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PostLikePO {

    private Long id;
    private String postId;
    private String userId;
    private LocalDateTime createdAt;
}
