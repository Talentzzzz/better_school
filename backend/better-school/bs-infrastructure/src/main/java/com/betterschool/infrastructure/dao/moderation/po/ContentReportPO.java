package com.betterschool.infrastructure.dao.moderation.po;

import lombok.Data;

import java.time.LocalDateTime;

/** content_report 举报单表 PO */
@Data
public class ContentReportPO {

    /** 业务 ID（UUID 字符串；uuid-biz-id 票 06：IIdGenerator 随 insert 一次写入，物理自增 id 保留纯内部键） */
    private String reportId;
    private String targetType;
    private String targetId;
    private String authorId;
    private String reporterId;
    private String reason;
    private String contentSnapshot;
    private String status;
    private String handlerId;
    private LocalDateTime handledAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 查询冗余字段：举报人/被举报人昵称（列表 JOIN 填充） */
    private String reporterNickname;
    private String authorNickname;
}
