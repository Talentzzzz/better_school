package com.betterschool.marketing.types.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * 幂等键的确定性映射：{@code hash(用户, 客户端 token)} → 36 位 UUID 规范形字符串。
 * <p>
 * 口径（票 13 定，uuid-biz-id 票 07 随全库 UUID 化改为字符串形态）：抽奖与兑次数两个写接口都
 * 要求前端为**每一次意图**生成一个 token，同一意图重试必须带同一个 token。本类把
 * 「(用户, token)」确定性地映射成一个业务主键（抽奖是 {@code draw_record.record_id}，
 * 兑次数是 {@code redeem_order_id} 口径的 refId），于是**重放不需要重放表**——落库的唯一键
 * 本身就是幂等互斥：同 token 重放恒映射同一 id，撞唯一键即判定为重放。
 * <p>
 * 取 SHA-256 全部 16 字节装进 {@link UUID#UUID(long, long)}（RFC 4122 版本位不刻意设置，
 * 只要求「确定性 + 全局唯一形状」），产出可被 {@link UUID#fromString} 解析的 36 位小写规范形，
 * 与 {@link IIdGenerator#nextId()} 的默认实现同形状。确定性映射保留（这是重放无需重放表的根），
 * 随机碰撞概率 ~2^-128 量级，真撞了由唯一键 + 归属校验兜底。
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    /** 把 (userId, token) 确定性映射成 36 位 UUID 字符串；token 为空抛 {@link IllegalArgumentException}（调用方先校验入参）。 */
    public static String of(String userId, String token) {
        if (userId == null || token == null || token.isBlank()) {
            throw new IllegalArgumentException("幂等键需要 userId 与非空 token");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((userId + ":" + token).getBytes(StandardCharsets.UTF_8));
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (hash[i] & 0xFFL);
            }
            for (int i = 8; i < 16; i++) {
                lsb = (lsb << 8) | (hash[i] & 0xFFL);
            }
            return new UUID(msb, lsb).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
