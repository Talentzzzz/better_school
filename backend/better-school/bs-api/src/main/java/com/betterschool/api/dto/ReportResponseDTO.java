package com.betterschool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 举报单（审核员列表） */
@Data
public class ReportResponseDTO {

    private Long id;
    /** 举报对象 POST/COMMENT/USER */
    private String targetType;
    private String targetId;
    private String authorId;
    private String authorNickname;
    private String reporterId;
    private String reporterNickname;
    private String reason;
    private String contentSnapshot;
    /** PENDING/PROCESSED/IGNORED */
    private String status;
    private String handlerId;
    private LocalDateTime handledAt;
    private LocalDateTime createdAt;
}
