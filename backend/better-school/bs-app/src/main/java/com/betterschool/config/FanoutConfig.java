package com.betterschool.config;

import com.betterschool.types.concurrent.VirtualThreadFanout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 调度扇出模板装配（CLAUDE.md config 约定：线程池等并发设施在这里装配）。
 * {@link VirtualThreadFanout} 无状态，可安全共享
 */
@Configuration
public class FanoutConfig {

    @Bean
    public VirtualThreadFanout virtualThreadFanout() {
        return new VirtualThreadFanout();
    }
}
