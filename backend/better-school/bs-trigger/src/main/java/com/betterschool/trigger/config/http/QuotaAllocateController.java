package com.betterschool.trigger.config.http;

import com.betterschool.api.response.Response;
import com.betterschool.usecase.inventory.QuotaAllocateCase;
import com.betterschool.trigger.http.security.SecurityUtils;
import com.betterschool.types.common.AppException;
import com.betterschool.types.enums.ResponseCode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * 奖品库存分配后台 HTTP 入口（award-inventory 两层模型）：把投放池余量分配给奖品位。
 * 写接口恒 ADMIN 校验（照 {@code MarketingConfigController} 的口径）。
 * <p>
 * 重试幂等：重复提交请带同一个 {@code allocBizId}（分配单号是两侧流水的唯一锚，
 * 撞单号按成功回放）；不带则每次都是新单。
 */
@RestController
@RequestMapping("/api/marketing/inventory/allocate")
@RequiredArgsConstructor
public class QuotaAllocateController {

    private final QuotaAllocateCase quotaAllocateCase;

    @PostMapping
    public Response<AllocateResponseDTO> allocate(@RequestParam String poolSegment,
                                                  @RequestParam String poolInvId,
                                                  @RequestParam String awardId,
                                                  @RequestParam long quantity,
                                                  @RequestParam(required = false) String allocBizId) {
        requireAdmin();
        boolean allocated = quotaAllocateCase.allocate(poolSegment, poolInvId, awardId, quantity, allocBizId);
        return Response.ok(new AllocateResponseDTO(allocated));
    }

    private static void requireAdmin() {
        if (!"ADMIN".equals(SecurityUtils.currentRole())) {
            throw new AppException(ResponseCode.FORBIDDEN, "无权限，仅管理员可分配奖品库存");
        }
    }

    /** 分配结果：true = 已转移（含同单号重放）；false = 池余量不足 */
    public static final class AllocateResponseDTO {

        private final boolean allocated;

        @JsonCreator
        public AllocateResponseDTO(@JsonProperty("allocated") boolean allocated) {
            this.allocated = allocated;
        }

        @JsonProperty("allocated")
        public boolean allocated() {
            return allocated;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AllocateResponseDTO other)) return false;
            return allocated == other.allocated;
        }

        @Override
        public int hashCode() {
            return Objects.hash(allocated);
        }

        @Override
        public String toString() {
            return "AllocateResponseDTO[allocated=" + allocated + "]";
        }
    }
}
