package com.betterschool.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class NotificationBroadcastRequestDTO {

    @NotBlank(message = "公告标题不能为空")
    @Size(max = 100, message = "标题最长 100 个字符")
    private String title;

    @NotBlank(message = "公告内容不能为空")
    @Size(max = 500, message = "内容最长 500 个字符")
    private String content;

    /** 通知类型，默认 ANNOUNCEMENT */
    private String type;
}
