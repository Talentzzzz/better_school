package com.betterschool.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品上架后编辑请求：全字段可选，null = 不改该项；
 * 编辑语义由服务端判定——仅 price 非空为改价（立即生效），title/description/images 任一非空为改内容（回待审）
 */
@Data
public class ProductEditRequestDTO {

    @Size(max = 30, message = "标题最长 30 个字符")
    private String title;

    @Size(max = 500, message = "描述最长 500 个字符")
    private String description;

    @Size(max = 9, message = "图片最多 9 张")
    private List<String> images;

    @DecimalMin(value = "0.01", message = "价格最低 0.01")
    private BigDecimal price;
}
