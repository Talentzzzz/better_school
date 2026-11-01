package com.betterschool.domain.community.adapter.port;

import com.betterschool.domain.community.model.valobj.CommunityUserVO;

/**
 * 用户只读查询端口（community 域的跨域只读声明）：补齐发布响应的作者昵称、读「公开我的赞过/评论」开关。
 * 由 infrastructure 实现（实现内只读 user 域），community 域只认这个接口与域内 VO；
 * 查不到返回 null，不抛异常——「谁可见」的判据留在 community 侧
 */
public interface IUserQueryPort {

    /** 单个用户概要；不存在返回 null（domain 侧再按 owner/viewer 与开关判可见性） */
    CommunityUserVO findById(String userId);
}
