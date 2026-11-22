package com.betterschool.api.lottery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 单抽请求：{@code token} 是前端生成的 uuid，**同一意图重试必须带同一个**——
 * 票 13 起 {@code (userId, token)} 经 SHA-256 确定性映射成 {@code draw_record.record_id}，
 * 撞 {@code uk_record_id} 即回放已存结果。
 * 这是本仓「幂等 + 重试」铁律在唯一一个「失败会让用户真亏一次抽奖」的写接口上的落地。
 */
@Data
public class DrawRequestDTO {

    @NotBlank(message = "缺少幂等 token")
    @Size(max = 64, message = "幂等 token 过长")
    private String token;
}
