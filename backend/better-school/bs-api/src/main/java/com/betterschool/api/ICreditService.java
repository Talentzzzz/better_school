package com.betterschool.api;

import com.betterschool.api.dto.AdminAdjustRequestDTO;
import com.betterschool.api.dto.AdminBanRequestDTO;
import com.betterschool.api.dto.AdminUnbanRequestDTO;
import com.betterschool.api.dto.CreditInfoResponseDTO;
import com.betterschool.api.dto.CreditLogResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;

/**
 * 信用分服务契约（credit 域）：HTTP 层由 Controller 实现，后续微服务化时由 Dubbo Provider 实现
 */
public interface ICreditService {

    /** 我的信用分与账号状态 */
    Response<CreditInfoResponseDTO> myCreditInfo();

    /** 我的信用分流水（倒序分页） */
    Response<PageResult<CreditLogResponseDTO>> myLogs(int page, int pageSize);

    /** 管理员调分（±1~±50，负分联动封禁） */
    Response<Void> adminAdjust(AdminAdjustRequestDTO adminAdjustRequestDTO);

    /** 管理员封禁 */
    Response<Void> adminBan(AdminBanRequestDTO adminBanRequestDTO);

    /** 管理员解封 */
    Response<Void> adminUnban(AdminUnbanRequestDTO adminUnbanRequestDTO);

    /** 管理员查指定用户信用分流水 */
    Response<PageResult<CreditLogResponseDTO>> adminLogs(String userId, int page, int pageSize);
}
