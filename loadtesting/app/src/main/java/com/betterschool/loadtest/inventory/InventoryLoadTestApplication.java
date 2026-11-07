package com.betterschool.loadtest.inventory;

import com.betterschool.loadtest.inventory.config.InventoryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 库存扣减三种策略对照压测工程。
 * <pre>
 *   db        纯 DB 行锁：一条 UPDATE 打同一行
 *   redis_mq  Redis 预扣 + 每条扣减发一条 MQ，消费端逐条扣 DB
 *   merge     Redis 桶预扣 + 落预扣明细 + 每秒一次时间格合并批量翻台账（线上同款）
 * </pre>
 * 三种策略共用同一套接口与同一张台账表，指标才可比。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(InventoryProperties.class)
public class InventoryLoadTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryLoadTestApplication.class, args);
    }
}
