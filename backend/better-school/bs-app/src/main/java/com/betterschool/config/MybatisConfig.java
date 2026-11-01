package com.betterschool.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.betterschool.infrastructure.dao")
public class MybatisConfig {
}
