package com.betterschool.config;

import com.betterschool.types.id.IIdGenerator;
import com.betterschool.types.id.UuidIdGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 业务 ID 发号器装配：全局唯一入口 {@link IIdGenerator}，默认 UUID 实现。
 * 注册等业务写入方注入本 bean 取号，业务 ID 在 insert 前一次写入（不再与物理自增同值回写）。
 */
@Configuration
public class IdGeneratorConfig {

    @Bean
    public IIdGenerator idGenerator() {
        return UuidIdGenerator.INSTANCE;
    }
}
