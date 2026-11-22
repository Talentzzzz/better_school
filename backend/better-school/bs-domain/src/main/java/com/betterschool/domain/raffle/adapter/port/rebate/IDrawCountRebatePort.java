package com.betterschool.domain.raffle.adapter.port.rebate;

/**
 * 抽奖域 → 返利域的只读端口（一域一仓储：域层只注入自己的仓储 + 自己的 port）。
 * <p>
 * 次数账户只关心「这个行为能发多少次」——查哪一种 {@code rebate_type} 是端口实现内部的事，
 * 域层不认识返利域的枚举。次数有效期不在这里：{@code draw_count_config} 归本域仓储直读。
 */
public interface IDrawCountRebatePort {

    /** 该行为下开启的次数返利值；没配或已关闭返回 null（= 这条行为不发） */
    Integer findOpenRebateValue(String actionType);
}
