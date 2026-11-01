package com.betterschool.domain.credit.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 信用分变动结果：case 层据此组装变动通知（含当前分与封禁提示） */
@Data
@AllArgsConstructor
public class CreditChangeResult {

    /** 变动后当前信用分 */
    private int currentScore;

    /** 本次变动是否触发低分封禁 */
    private boolean justBanned;
}
