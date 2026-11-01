package com.betterschool.api;

import com.betterschool.api.dto.UserBasicDTO;

import java.util.Collection;
import java.util.List;

/**
 * 用户只读查询契约（同步边 / Dubbo）：营销三服务取昵称、头像、注册时间。
 * 与 IUserService.getProfile 的区别：这里只对外暴露「跨服务必需」的字段，
 * 且 <b>不抛 404</b>——查不到的 id 直接跳过，避免一个脏 id 打挂批量调用。
 * <p>
 * 不实现 IUserService 的原因见 marketing spec「同步边（Dubbo）」：getProfile 无 createdAt，
 * 且带 receivedLikeCount / 隐私开关等营销域用不到的字段。
 */
public interface IUserQueryService {

    /** 单个用户基本信息；userId 为空或用户不存在返回 null */
    UserBasicDTO getBasic(String userId);

    /** 批量用户基本信息；返回顺序与入参无关（调用方按 userId 自行索引），不存在的 id 直接跳过 */
    List<UserBasicDTO> getBasicBatch(Collection<String> userIds);
}
