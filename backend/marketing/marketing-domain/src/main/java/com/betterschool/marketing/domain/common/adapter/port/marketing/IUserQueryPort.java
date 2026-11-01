package com.betterschool.marketing.domain.common.adapter.port.marketing;

import com.betterschool.marketing.domain.common.model.valobj.marketing.UserBasicVO;

/**
 * 用户只读查询端口（同步边）：由 infrastructure 用 Dubbo 实现，域层只认这个接口与域内 VO。
 * 查不到的用户返回 null，不抛异常——跨服务读不该因一个脏 id 打挂整条链路。
 */
public interface IUserQueryPort {

    UserBasicVO getBasic(String userId);
}
