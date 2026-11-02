package com.betterschool.marketing.config;

import com.betterschool.marketing.types.id.IIdGenerator;
import com.betterschool.marketing.types.id.UuidIdGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 业务 ID 发号器装配（uuid-biz-id 票 07）：全局唯一入口 {@link IIdGenerator}，默认 UUID 实现。
 * bucket_id 等业务写入方注入本 bean 取号，业务 ID 在 insert 前一次写入；
 * {@code SnowflakeIdGenerator} 随本票退役删除。
 */
@Configuration
public class IdGeneratorConfig {

    @Bean
    public IIdGenerator idGenerator() {
        return UuidIdGenerator.INSTANCE;
    }
}
