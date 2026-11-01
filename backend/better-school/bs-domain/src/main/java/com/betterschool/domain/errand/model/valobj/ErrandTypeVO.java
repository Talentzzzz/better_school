package com.betterschool.domain.errand.model.valobj;

import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrandTypeVO {

    EXPRESS_PICKUP("代取快递"),
    BUY("代买"),
    DELIVER("送件"),
    OTHER("其他");

    private final String desc;

    public static ErrandTypeVO from(String name) {
        try {
            return ErrandTypeVO.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "跑腿类型不合法");
        }
    }
}
