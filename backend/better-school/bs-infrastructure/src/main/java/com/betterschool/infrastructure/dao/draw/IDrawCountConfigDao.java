package com.betterschool.infrastructure.dao.draw;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 抽奖次数有效期配置表（draw_count_config）Mapper：一表一 DAO。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/draw/DrawCountConfigMapper.xml。
 */
@Mapper
public interface IDrawCountConfigDao {

    Integer selectDrawCountValidDays();

    int updateDrawCountValidDays(@Param("drawCountValidDays") int drawCountValidDays);
}
