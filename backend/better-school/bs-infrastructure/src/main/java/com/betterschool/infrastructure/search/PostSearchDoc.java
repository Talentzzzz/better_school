package com.betterschool.infrastructure.search;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 帖子索引文档：仅 id + 检索/过滤字段；展示字段（点赞数 / 作者昵称头像）由业务库反查组装，避免双写漂移。
 * 技术组件内部结构，领域层不引用（domain 不依赖 infrastructure）
 */
@Data
public class PostSearchDoc {

    /** 帖子业务 ID（UUID 字符串，ES 侧 keyword 字段，与 DB 同表示） */
    private String postId;
    private String title;
    private String content;
    /** 过滤字段：状态（仅 NORMAL 入库，状态变更由同步事件修正） */
    private String status;
    private LocalDateTime createdAt;
}
