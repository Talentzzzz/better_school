package com.betterschool.marketing.types.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等键的确定性映射：{@code hash(用户, 客户端 token)} → 正 long。
 * <p>
 * 口径（票 13 定）：抽奖与兑次数两个写接口都要求前端为**每一次意图**生成一个 token，
 * 同一意图重试必须带同一个 token。本类把「(用户, token)」确定性地映射成一个业务主键
 * （抽奖是 {@code draw_record.record_id}，兑次数是 {@code redeem_order_id} 口径的 refId），
 * 于是**重放不需要重放表**——落库的唯一键本身就是幂等互斥：
 * 同 token 重放恒映射同一 id，撞唯一键即判定为重放。
 * <p>
 * 取 SHA-256 前 8 字节并清掉符号位（{@code & Long.MAX_VALUE}），0 抬成 1；
 * 碰撞概率 ~2^-63 量级，真撞了由唯一键 + 归属校验兜底。
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    /** 把 (userId, token) 确定性映射成正 long；token 为空抛 {@link IllegalArgumentException}（调用方先校验入参）。 */
    public static long of(String userId, String token) {
        if (userId == null || token == null || token.isBlank()) {
            throw new IllegalArgumentException("幂等键需要 userId 与非空 token");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((userId + ":" + token).getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (hash[i] & 0xFFL);
            }
            value &= Long.MAX_VALUE;
            return value == 0 ? 1 : value;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
