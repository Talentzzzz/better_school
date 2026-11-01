package com.betterschool.marketing.domain.raffle.adapter.port.config;

/**
 * 抽奖域 → 配置域的只读端口（一域一仓储：域层只注入自己的仓储 + 自己的 port）。
 * 抽奖读侧只需要「抽奖次数有效期天数」这一项展示口径，就只开这一个方法；
 * 实现在 infrastructure，只读配置域仓储后返回数值。
 */
public interface IRaffleConfigPort {

    /** 抽奖次数有效期天数（draw_count_config 单行配置） */
    int drawCountValidDays();
}
