package com.betterschool.marketing.domain.drawcount.adapter.port.config;

import com.betterschool.marketing.domain.config.model.valobj.RebateTypeVO;

/**
 * 抽奖次数域 → 配置域的只读端口（一域一仓储：域层只注入自己的仓储 + 自己的 port）。
 * 次数账户要用两项配置：次数有效期天数、某行为的返利次数；就只开这两个方法，
 * 实现在 infrastructure，只读配置域仓储取数，判据留在次数域。
 */
public interface IDrawCountConfigPort {

    /** 抽奖次数有效期天数（draw_count_config 单行配置） */
    int drawCountValidDays();

    /** 该行为下开启的返利面额；没配或已关闭返回 null（= 这条行为不发） */
    Integer findOpenRebateValue(String actionType, RebateTypeVO rebateType);
}
