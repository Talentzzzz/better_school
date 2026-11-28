package com.betterschool.trigger.config.http;

import com.betterschool.api.response.Response;
import com.betterschool.usecase.award.AwardInventoryCase;
import com.betterschool.trigger.http.security.SecurityUtils;
import com.betterschool.types.common.AppException;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * 奖品库存维护后台 HTTP 入口：直接维护奖品位库存账户（投放 / 改量 / 关户）。
 * 写接口恒 ADMIN 校验（照 {@code MarketingConfigController} 的口径）。
 */
@RestController
@RequestMapping("/api/marketing/award/inventory")
@RequiredArgsConstructor
public class AwardInventoryController {

    private final AwardInventoryCase awardInventoryCase;

    @PostMapping
    public Response<UpsertResponseDTO> upsert(@RequestParam String awardId,
                                              @RequestParam String type,
                                              @RequestParam Long surplusQty,
                                              @RequestParam(required = false) Long valueQty,
                                              @RequestParam(required = false) Integer state) {
        requireAdmin();
        boolean saved = awardInventoryCase.upsert(awardId, type, surplusQty, valueQty, state);
        return Response.ok(new UpsertResponseDTO(saved));
    }

    private static void requireAdmin() {
        if (!"ADMIN".equals(SecurityUtils.currentRole())) {
            throw new AppException(ResponseCodeEnum.FORBIDDEN, "无权限，仅管理员可维护奖品库存");
        }
    }

    /** 维护结果：true = 已写入（新建或更新） */
    public static final class UpsertResponseDTO {

        private final boolean saved;

        @JsonCreator
        public UpsertResponseDTO(@JsonProperty("saved") boolean saved) {
            this.saved = saved;
        }

        @JsonProperty("saved")
        public boolean saved() {
            return saved;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof UpsertResponseDTO upsertResponseDTO)) return false;
            return saved == upsertResponseDTO.saved;
        }

        @Override
        public int hashCode() {
            return Objects.hash(saved);
        }

        @Override
        public String toString() {
            return "UpsertResponseDTO[saved=" + saved + "]";
        }
    }
}
