package com.betterschool.marketing.domain.raffle.adapter.port.marketing;

import com.betterschool.marketing.domain.raffle.model.valobj.RaffleUserVO;

/**
 * 用户只读查询端口（抽奖域 → 单体用户中心的跨服务边）：由 infrastructure 用 Dubbo 实现，
 * 域层只认这个接口与域内 VO。
 * <p>
 * 查不到的用户返回 null，不抛异常——跨服务读不该因一个脏 id 打挂整条链路。
 */
public interface IUserQueryPort {

    RaffleUserVO getBasic(String userId);
}
