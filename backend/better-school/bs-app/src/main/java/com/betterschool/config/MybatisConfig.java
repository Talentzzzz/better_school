package com.betterschool.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * Mapper 扫描：DAO 接口按域分落（infrastructure.dao.{chat,credit,...} 与营销四域
 * dao.{inventory,points,draw,rebate}，域段插在 dao 后；抽奖次数表 draw_count_* 归 draw 域）。
 * MapperScan 基包递归扫描子包，单体平铺域与营销并入域共用一个基包即可。
 * nameGenerator 用 FQCN 兜底：跨域同名 mapper 接口若再出现，默认简单类名派生会撞 bean 名
 * （营销侧 MybatisConfig 既有先例）。
 */
@Configuration
@MapperScan(value = "com.betterschool.infrastructure.dao", nameGenerator = FqcnAnnotationBeanNameGenerator.class)
public class MybatisConfig {
}
