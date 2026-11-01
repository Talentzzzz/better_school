package com.betterschool.domain.moderation.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 举报处理结果：跨域编排"外部动作需求"载体（case 层据此走 下架/扣分/通知 编排）。
 * 幂等语义：processReport 条件 UPDATE 0 行（重试）时仍返回准确需求，靠 credit 流水 uk 兜底不丢扣分
 */
@Data
@AllArgsConstructor
public class ReportProcessResult {

    /** 处理后状态（PROCESSED=核实 / IGNORED=忽略） */
    private ReportStatusVO status;

    private ReviewTargetTypeVO targetType;

    private String targetId;

    /** 被举报人（content_report 冗余，处理时零反查） */
    private String authorId;

    /** 举报人（通知举报人处理结果用） */
    private String reporterId;

    /** 是否还需要扣分（同 target 所有举报单均未产生过 REPORT_CONFIRMED 流水才为 true，一内容一罚） */
    private boolean needDeduct;
}
