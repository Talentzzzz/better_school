package com.betterschool.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 跑腿草稿保存请求：全部字段可缺省（未填完就存），仅保留长度/下限约束；
 * 提交时的完整性校验（必填 + 同步初筛）在用例层做（必填 = 直接发布的必填项）
 */
@Data
public class ErrandDraftSaveRequestDTO {

    private String type;

    @Size(max = 200, message = "任务描述最长 200 个字符")
    private String description;

    @Size(max = 100, message = "取件地点最长 100 个字符")
    private String pickupLocation;

    @Size(max = 100, message = "送达地点最长 100 个字符")
    private String deliveryLocation;

    @DecimalMin(value = "10", message = "悬赏金额最低 10")
    private BigDecimal reward;

    private String timeLimit;

    @Size(max = 6, message = "配图最多 6 张")
    private List<String> images;
}
