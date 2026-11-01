package com.betterschool.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ProductPublishRequestDTO {

    @NotBlank(message = "标题不能为空")
    @Size(max = 30, message = "标题最长 30 个字符")
    private String title;

    @Size(max = 500, message = "描述最长 500 个字符")
    private String description;

    @Size(max = 9, message = "图片最多 9 张")
    private List<String> images;

    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.01", message = "价格最低 0.01")
    private BigDecimal price;

    @NotBlank(message = "交易地点不能为空")
    @Size(max = 100, message = "交易地点最长 100 个字符")
    private String tradeLocation;

    @NotBlank(message = "分类不能为空")
    private String category;
}
