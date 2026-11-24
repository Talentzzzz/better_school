package com.betterschool.domain.idle.model.valobj;

import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ProductCategoryVO {

    DIGITAL("数码"),
    BOOKS("书籍"),
    DAILY("生活用品"),
    SPORTS("运动"),
    TICKETS("票券"),
    OTHER("其他");

    private final String desc;

    public static ProductCategoryVO from(String name) {
        try {
            return ProductCategoryVO.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "商品分类不合法");
        }
    }
}
