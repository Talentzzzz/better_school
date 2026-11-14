package com.betterschool.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ErrandOrderPublishRequestDTO {

    @NotBlank(message = "标题不能为空")
    @Size(max = 50, message = "标题最长 50 个字符")
    private String title;

    @NotBlank(message = "跑腿类型不能为空")
    private String type;

    @NotBlank(message = "任务描述不能为空")
    @Size(max = 200, message = "任务描述最长 200 个字符")
    private String description;

    @NotBlank(message = "取件地点不能为空")
    @Size(max = 100, message = "取件地点最长 100 个字符")
    private String pickupLocation;

    @NotBlank(message = "送达地点不能为空")
    @Size(max = 100, message = "送达地点最长 100 个字符")
    private String deliveryLocation;

    @NotNull(message = "悬赏金额不能为空")
    @DecimalMin(value = "10", message = "悬赏金额最低 10")
    private BigDecimal reward;

    /** 截止时间（ISO 绝对时刻；区间校验 15min~7d 对照数据库时钟，在仓储层做） */
    @NotNull(message = "截止时间不能为空")
    private LocalDateTime deadline;

    @Size(max = 6, message = "配图最多 6 张")
    private List<String> images;
}
