package com.betterschool.api;

import com.betterschool.api.dto.DeadLetterResendResultDTO;
import com.betterschool.api.dto.DeadLetterTaskDTO;
import com.betterschool.api.dto.TaskKeyDTO;
import com.betterschool.api.dto.TaskKeysRequestDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;

/**
 * 超时中心死信管理契约（内嵌化票 06）：回调连续失败进死信后的人工出口，纯运维面。
 * 登录态由安全过滤链要求（未登录 401），角色在控制器收口 ADMIN。
 */
public interface ITimeoutTaskAdminService {

    /** 死信列表：updated_at 倒序；分页参数越界夹住不报错（每页 ≤50、页码 ≤1000） */
    Response<PageResult<DeadLetterTaskDTO>> listDeadLetters(int page, int pageSize);

    /** 批量重发死信（单条也放进列表）；failList = 该键当前不是 FAILED，无副作用 */
    Response<DeadLetterResendResultDTO> resendDeadLetters(TaskKeysRequestDTO taskKeysRequestDTO);

    /** 取消死信（FAILED → CANCELED）；返回 false = 该键当前不是 FAILED，无副作用 */
    Response<Boolean> cancelDeadLetter(TaskKeyDTO taskKeyDTO);
}
