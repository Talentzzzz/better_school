package com.betterschool.domain.post.adapter.port;

import com.betterschool.domain.post.model.valobj.PostUserVO;

/**
 * 用户只读查询端口（post 域的跨域只读声明）：补齐发布响应的作者昵称、读「公开我的赞过/评论」开关。
 * 由 infrastructure 实现（实现内只读 user 域），post 域只认这个接口与域内 VO；
 * 查不到返回 null，不抛异常——「谁可见」的判据留在 post 侧
 */
public interface IUserQueryPort {

    /** 单个用户概要；不存在返回 null（domain 侧再按 owner/viewer 与开关判可见性） */
    PostUserVO findById(String userId);
}
