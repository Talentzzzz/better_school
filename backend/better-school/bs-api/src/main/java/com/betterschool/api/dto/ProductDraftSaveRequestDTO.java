package com.betterschool.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品草稿保存请求：全部字段可缺省（未填完就存），仅保留长度/下限约束；
 * 提交时的完整性校验在用例层做（必填 = 直接发布的必填项）
 */
@Data
public class ProductDraftSaveRequestDTO {

    @Size(max = 30, message = "标题最长 30 个字符")
    private String title;

    @Size(max = 500, message = "描述最长 500 个字符")
    private String description;

    @Size(max = 9, message = "图片最多 9 张")
    private List<String> images;

    @DecimalMin(value = "0.01", message = "价格最低 0.01")
    private BigDecimal price;

    @Size(max = 100, message = "交易地点最长 100 个字符")
    private String tradeLocation;

    private String category;
}
