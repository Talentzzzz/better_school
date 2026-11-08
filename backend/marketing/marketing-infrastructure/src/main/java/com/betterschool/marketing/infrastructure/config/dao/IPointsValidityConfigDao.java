package com.betterschool.marketing.infrastructure.config.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 积分有效期配置表（points_validity_config）Mapper：一表一 DAO。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/PointsValidityConfigMapper.xml。
 */
@Mapper
public interface IPointsValidityConfigDao {

    Integer selectPointsValidDays();

    int updatePointsValidDays(@Param("pointsValidDays") int pointsValidDays);
}
