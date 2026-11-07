package com.betterschool.loadtest.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * 两段 Lua 脚本：
 * <ul>
 *   <li>merge：扣 + 决定要不要叫醒合并（返回 {code, notify}）</li>
 *   <li>plain：扣（redis_mq 策略用，没有合并窗口）</li>
 * </ul>
 * 两段都必须单脚本原子执行，否则「查余量 → 递减」之间会被并发插队超卖。
 */
@Configuration
public class RedisScriptConfig {

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public RedisScript<List> mergeDeductScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/inventory_bucket_deduct.lua"));
        script.setResultType(List.class);
        return (RedisScript) script;
    }

    @Bean
    public RedisScript<Long> plainDeductScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/inventory_bucket_deduct_plain.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
