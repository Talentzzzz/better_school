package com.betterschool.trigger.http.timeout;

import com.betterschool.api.ITimeoutTaskAdminService;
import com.betterschool.api.dto.DeadLetterResendResultDTO;
import com.betterschool.api.dto.DeadLetterTaskDTO;
import com.betterschool.api.dto.TaskKeyDTO;
import com.betterschool.api.dto.TaskKeysRequestDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;
import com.betterschool.infrastructure.timeout.DeadLetterPage;
import com.betterschool.infrastructure.timeout.DeadLetterResendOutcome;
import com.betterschool.infrastructure.timeout.TaskKey;
import com.betterschool.infrastructure.timeout.TimeoutDeadLetterAdmin;
import com.betterschool.infrastructure.timeout.TimeoutTaskEntity;
import com.betterschool.trigger.http.security.SecurityUtils;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 死信管理 HTTP 入口（内嵌化票 06）：死信列表 / 单条与批量重发 / 手动取消。
 * <p>
 * **落点（票 06 拍板）**：管理面只落在主体工程、走单体对外端口 8080 的
 * {@code /api/admin/timeout-tasks/**}——中心内嵌后「8083 内网端口」不复存在，
 * 原独立中心的同款入口随工程下线。营销侧本期不落管理面（零超时接入方，票 08 只定规格）；
 * 口径是「谁有超时表、谁才有管理面」，营销将来接入时按同款落。
 * <p>
 * **鉴权（票 06 拍板）**：接单体的 JWT/角色设施——登录态由 SecurityConfig 的 JWT 过滤链罩住
 * （{@code anyRequest().authenticated()}），角色在这里显式收 ADMIN（与 {@code AdminReviewController}
 * 同款，403 走统一响应体）。原独立中心「无鉴权 + 部署侧收紧」的缺口随落点迁移一并关掉。
 * <p>
 * **分页钳制（票 06 拍板：保留）**：列表仍是跨 16 分表的广播归并（票面「退化成单表分页」的
 * 前提不成立——内嵌化只是把分表搬进业务库，分表本身没消失），且现在与用户请求共享池 10，
 * 深翻页的代价从「把中心读垮」升级成「和业务流量抢连接」。夹住而不是报错：管理面翻页翻到
 * 上千页本来就说明该去按行处理而不是继续翻。
 * <p>
 * 重发与取消的响应里不返回「成功/失败」文字：「失败」在这两条路都是**无副作用**的正常结果
 * （该键当前不是 FAILED——被人处理过、或键写错了），逐项回报双清单 / false 就够了。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/timeout-tasks")
@RequiredArgsConstructor
public class TimeoutTaskAdminController implements ITimeoutTaskAdminService {

    /** 每页上限：广播一次 = 16 张分表各扫一遍，页宽放大是 16 倍的读放大 */
    private static final int MAX_PAGE_SIZE = 50;

    /**
     * 页码上限：LIMIT/OFFSET 的跳过行数在每张分表上都要付一遍，深页码是 O(页码 × 页宽 × 16) 的读。
     */
    private static final int MAX_PAGE = 1000;

    private final TimeoutDeadLetterAdmin timeoutDeadLetterAdmin;

    /** 死信列表：按最近进死信的顺序翻页（{@code updated_at} 倒序），分页参数在这里夹紧 */
    @Override
    @GetMapping("/dead-letters")
    public Response<PageResult<DeadLetterTaskDTO>> listDeadLetters(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        requireAdmin();
        int safePage = Math.min(Math.max(page, 1), MAX_PAGE);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        DeadLetterPage deadLetterPage = timeoutDeadLetterAdmin.page(safePage, safePageSize);
        return Response.ok(new PageResult<>(safePage, safePageSize, deadLetterPage.getTotal(),
                deadLetterPage.getTasks().stream().map(TimeoutTaskAdminController::toDTO).toList()));
    }

    /**
     * 重发死信（单条 = 单元素列表）：FAILED → RETRYING、retry_count 清零，下一个扫描周期即重投
     * （走同一个扫描 job 入口，不新开重试通路）。
     */
    @Override
    @PostMapping("/dead-letters/resend")
    public Response<DeadLetterResendResultDTO> resendDeadLetters(@RequestBody TaskKeysRequestDTO taskKeysRequestDTO) {
        requireAdmin();
        if (taskKeysRequestDTO == null || taskKeysRequestDTO.getKeys() == null || taskKeysRequestDTO.getKeys().isEmpty()) {
            throw illegalParam("重发入参缺 keys（单条也请放进列表）");
        }
        List<TaskKey> keys = taskKeysRequestDTO.getKeys().stream().map(TimeoutTaskAdminController::toKey).toList();
        DeadLetterResendOutcome deadLetterResendOutcome = timeoutDeadLetterAdmin.resend(keys);

        DeadLetterResendResultDTO deadLetterResendResultDTO = new DeadLetterResendResultDTO();
        deadLetterResendOutcome.getSuccessList().forEach(key -> deadLetterResendResultDTO.getSuccessList().add(toKeyDTO(key)));
        deadLetterResendOutcome.getFailList().forEach(key -> deadLetterResendResultDTO.getFailList().add(toKeyDTO(key)));
        return Response.ok(deadLetterResendResultDTO);
    }

    /** 手动取消死信：FAILED → CANCELED 终态。返回 false = 该键当前不是 FAILED，无副作用。 */
    @Override
    @PostMapping("/dead-letters/cancel")
    public Response<Boolean> cancelDeadLetter(@RequestBody TaskKeyDTO taskKeyDTO) {
        requireAdmin();
        if (taskKeyDTO == null) {
            throw illegalParam("取消入参为空");
        }
        boolean canceled = timeoutDeadLetterAdmin.cancel(
                requireText(taskKeyDTO.getTaskType(), "taskType"), requireText(taskKeyDTO.getBizId(), "bizId"));
        return Response.ok(canceled);
    }

    /** 管理面只对管理员开放（与 AdminReviewController 同款；未登录已被过滤链 401 挡下） */
    private static void requireAdmin() {
        if (!"ADMIN".equals(SecurityUtils.currentRole())) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "仅管理员可操作");
        }
    }

    private static TaskKey toKey(TaskKeyDTO taskKeyDTO) {
        if (taskKeyDTO == null) {
            throw illegalParam("重发入参含空键");
        }
        return TaskKey.of(requireText(taskKeyDTO.getTaskType(), "taskType"), requireText(taskKeyDTO.getBizId(), "bizId"));
    }

    private static TaskKeyDTO toKeyDTO(TaskKey taskKey) {
        return new TaskKeyDTO(taskKey.getTaskType(), taskKey.getBizId());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw illegalParam("入参缺 " + field);
        }
        return value;
    }

    private static BusinessException illegalParam(String detail) {
        log.warn("[超时中心告警] 死信管理入参不合法：{}", detail);
        return new BusinessException(ResponseCodeEnum.PARAM_ERROR, detail);
    }

    /** 实体 → 只读 DTO（死信的 status 恒为 FAILED，不占一列） */
    private static DeadLetterTaskDTO toDTO(TimeoutTaskEntity timeoutTaskEntity) {
        DeadLetterTaskDTO deadLetterTaskDTO = new DeadLetterTaskDTO();
        deadLetterTaskDTO.setTaskType(timeoutTaskEntity.getTaskType());
        deadLetterTaskDTO.setBizId(timeoutTaskEntity.getBizId());
        deadLetterTaskDTO.setActionTime(timeoutTaskEntity.getActionTime());
        deadLetterTaskDTO.setNextFireTime(timeoutTaskEntity.getNextFireTime());
        deadLetterTaskDTO.setRetryCount(timeoutTaskEntity.getRetryCount());
        deadLetterTaskDTO.setCreatedAt(timeoutTaskEntity.getCreatedAt());
        deadLetterTaskDTO.setUpdatedAt(timeoutTaskEntity.getUpdatedAt());
        return deadLetterTaskDTO;
    }
}
