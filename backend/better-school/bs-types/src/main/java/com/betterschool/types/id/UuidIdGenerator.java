package com.betterschool.types.id;

import java.util.UUID;

/**
 * {@link IIdGenerator} 的默认实现：{@code UUID.randomUUID().toString()}。
 * <p>
 * 36 位连字符小写规范形，可被 {@code UUID.fromString} 解析；随机性承担「雪花式全局唯一」语义
 * （无协调器、分库后仍可发号），无序性由调用方按「时间 + 业务 ID」复合键收敛确定序。
 * 单例无状态、线程安全（{@code UUID.randomUUID} 内部经 ThreadLocalRandom）。
 */
public final class UuidIdGenerator implements IIdGenerator {

    public static final UuidIdGenerator INSTANCE = new UuidIdGenerator();

    @Override
    public String nextId() {
        return UUID.randomUUID().toString();
    }
}
