package com.betterschool.marketing.infrastructure.config.dao;

import com.betterschool.marketing.infrastructure.config.dao.po.ActionRebateConfigPO;
import com.betterschool.marketing.infrastructure.config.dao.po.DrawCountConfigPO;
import com.betterschool.marketing.infrastructure.config.dao.po.PointsValidityConfigPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 配置 Mapper（积分/次数有效期单行配置 + 行为返利规则，一域一 DAO）。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/ConfigMapper.xml。
 */
@Mapper
public interface IConfigDao {

    Integer selectPointsValidDays();

    int updatePointsValidDays(@Param("pointsValidDays") int pointsValidDays);

    Integer selectDrawCountValidDays();

    int updateDrawCountValidDays(@Param("drawCountValidDays") int drawCountValidDays);

    /** 某行为某返利类型的开启规则值（state=open）：未配置或已关闭返回 null */
    Integer selectOpenRebateValue(@Param("actionType") String actionType,
                                  @Param("rebateType") String rebateType);

    List<ActionRebateConfigPO> selectRebates();

    /** 按 uk (action_type, rebate_type) 取一条规则（不存在返回 null） */
    ActionRebateConfigPO selectRebate(@Param("actionType") String actionType,
                                      @Param("rebateType") String rebateType);

    /** 按 uk (action_type, rebate_type) upsert：命中已存在行时更新其余列 */
    int upsertRebate(ActionRebateConfigPO rebatePO);
}
