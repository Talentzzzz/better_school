package com.betterschool.marketing.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * Mapper 扫描：DAO 接口按域分落（marketing.infrastructure.{points,rebate,raffle,inventory}.dao 与
 * marketing.infrastructure.common.dao.outbox，域段插在 dao 前；抽奖次数表 draw_count_* 归 raffle 域），
 * 扫描基包必须逐域列出，不能扫它们的伪公共前缀 marketing.infrastructure.dao
 * （该前缀在磁盘上不存在）。SQL 在本模块 resources/mybatis/mapper/。
 * nameGenerator 用 FQCN：IOutboxDao / IRaffleDao 等按域同名（票 07 落地），
 * 默认简单类名派生 mapper bean 名会冲突。
 * （旧 lottery.dao 段随票 13 删除旧域代码一并移除。）
 */
@Configuration
@MapperScan(value = {"com.betterschool.marketing.infrastructure.points.dao",
        "com.betterschool.marketing.infrastructure.rebate.dao",
        "com.betterschool.marketing.infrastructure.raffle.dao",
        "com.betterschool.marketing.infrastructure.inventory.dao",
        "com.betterschool.marketing.infrastructure.common.dao.outbox"},
        nameGenerator = FqcnAnnotationBeanNameGenerator.class)
public class MybatisConfig {
}
