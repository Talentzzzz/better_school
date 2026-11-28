package com.betterschool.domain.idle.model.valobj;

import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ProductCategoryEnum {

    DIGITAL("数码"),
    BOOKS("书籍"),
    DAILY("生活用品"),
    SPORTS("运动"),
    TICKETS("票券"),
    OTHER("其他");

    private final String desc;

    public static ProductCategoryEnum from(String name) {
        try {
            return ProductCategoryEnum.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "商品分类不合法");
        }
    }
}
