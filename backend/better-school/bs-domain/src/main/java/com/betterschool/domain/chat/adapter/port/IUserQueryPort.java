package com.betterschool.domain.chat.adapter.port;

import com.betterschool.domain.chat.model.valobj.ChatUserVO;

import java.util.List;

/**
 * 用户只读查询端口（chat 域的跨域只读声明）：存在性 + 批量昵称头像。
 * 由 infrastructure 实现（实现内只读 user 域），chat 域只认这个接口与域内 VO；
 * 查不到返回 null / 缺项，不抛异常——存在性判据留在 chat 侧（建会话用例判 null 才报"用户不存在"）
 */
public interface IUserQueryPort {

    /** 单个用户资料；不存在返回 null */
    ChatUserVO findById(String userId);

    /** 批量用户资料（会话列表/成员列表拼昵称头像；返回顺序与入参无关，调用方按 userId 重排） */
    List<ChatUserVO> listByIds(List<String> userIds);
}
