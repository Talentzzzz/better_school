package com.betterschool.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ErrandOrderPublishRequestDTO {

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

    @NotBlank(message = "时限不能为空")
    private String timeLimit;

    @Size(max = 6, message = "配图最多 6 张")
    private List<String> images;
}
