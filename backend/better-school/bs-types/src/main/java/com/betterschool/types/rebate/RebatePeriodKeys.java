package com.betterschool.types.rebate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 行为返利限次周期键（日限口径 {@code yyyyMMdd}，时区固定 {@link #ZONE}）。
 * <p>
 * 周期键在发帖/提交事务里按数据库时钟的发布时间定死并随领取任务落库；重放使用任务里的原值，
 * 不按重放当天重算，避免跨零点串天。周期键 = Asia/Shanghai 日历日；入参是数据库时钟的墙钟时间
 * （部署 TZ=Asia/Shanghai，见 docker-compose），格式化全程显式按 {@link #ZONE} 取日期，
 * 从不读 JVM 默认时区。
 */
public final class RebatePeriodKeys {

    /** 日限口径时区：每天 = Asia/Shanghai 自然日 0 点 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter PERIOD_KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private RebatePeriodKeys() {
    }

    /**
     * 由已落库的发布时间（数据库时钟的 {@link LocalDateTime}，部署 TZ=Asia/Shanghai）格式化为 {@code yyyyMMdd}。
     * 显式按 {@link #ZONE} 取日期、不读 JVM 默认时区；入参已是墙钟时间，故语义与直接格式化一致。
     *
     * @throws NullPointerException publishTime 为 null
     */
    public static String of(LocalDateTime publishTime) {
        Objects.requireNonNull(publishTime, "publishTime 不能为空");
        return publishTime.atZone(ZONE).format(PERIOD_KEY_FORMATTER);
    }

    /** 当天兜底（仅旧载荷缺 periodKey / 重放兜底用），显式 {@link #ZONE} */
    public static String today() {
        return LocalDateTime.now(ZONE).format(PERIOD_KEY_FORMATTER);
    }
}
