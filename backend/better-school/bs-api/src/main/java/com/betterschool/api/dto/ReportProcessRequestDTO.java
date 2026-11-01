package com.betterschool.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 举报处理（审核员）：核实违规 / 不实忽略 */
@Data
public class ReportProcessRequestDTO {

    /** 处理动作 PROCESSED(核实)/IGNORED(忽略) */
    @NotBlank(message = "处理动作必填")
    private String action;
}
