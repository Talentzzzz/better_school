package com.betterschool.domain.points.adapter.port.rebate;

/**
 * 积分域 → 返利域的只读端口（一域一仓储：域层只注入自己的仓储 + 自己的 port）。
 * <p>
 * 积分账户只关心「这个行为能发多少积分」——查哪一种 {@code rebate_type} 是端口实现内部的事，
 * 域层不认识返利域的枚举（判据留在消费方域，转换压在适配器里）。
 * 积分有效期不在这里：{@code points_validity_config} 归本域仓储直读。
 */
public interface IPointsRebatePort {

    /** 该行为下开启的积分返利面额；没配或已关闭返回 null（= 这条行为不发） */
    Integer findOpenRebateValue(String actionType);
}
