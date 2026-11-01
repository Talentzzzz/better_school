package com.betterschool.domain.search.model.valobj;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 帖子索引文档：仅 id + 检索/过滤字段；展示字段（likeCount/作者昵称头像）由 MySQL 反查组装，避免双写漂移
 */
@Data
public class PostSearchDocVO {

    /** 帖子业务 ID（UUID 字符串，ES 侧 keyword 字段，与 DB 同表示） */
    private String postId;
    private String title;
    private String content;
    /** 过滤字段：状态（仅 NORMAL 入库，状态变更由同步事件修正） */
    private String status;
    private LocalDateTime createdAt;
}
