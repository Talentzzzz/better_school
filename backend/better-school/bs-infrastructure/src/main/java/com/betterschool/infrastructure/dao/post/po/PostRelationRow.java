package com.betterschool.infrastructure.dao.post.po;

import lombok.Data;

/**
 * 关系表主查行（我赞过的 / 我评论过的 / 浏览历史）：关系表单表扫描只投影业务 id，
 * 排序在 SQL 内完成（ORDER BY 排序键, post_id），行序即页面序；帖子行由仓储按 id 集二次批量回查拼装（SQL 层零 JOIN）。
 * 三类关系共用一个载体——排序列不随行带出，故此处不映射排序键字段。
 */
@Data
public class PostRelationRow {

    private String postId;
}
