package com.betterschool.infrastructure.dao.rebate;

import com.betterschool.infrastructure.dao.rebate.po.ActionRebateConfigPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 行为返利配置表（action_rebate_config）Mapper：一表一 DAO。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/ActionRebateConfigMapper.xml。
 */
@Mapper
public interface IActionRebateConfigDao {

    /** 某行为某返利类型的开启规则值（state=open）：未配置或已关闭返回 null */
    Integer selectOpenRebateValue(@Param("actionType") String actionType,
                                  @Param("rebateType") String rebateType);

    List<ActionRebateConfigPO> selectRebates();

    /** 按 uk (action_type, rebate_type) 取一条规则（不存在返回 null） */
    ActionRebateConfigPO selectRebate(@Param("actionType") String actionType,
                                      @Param("rebateType") String rebateType);

    /** 按 uk (action_type, rebate_type) upsert：命中已存在行时更新其余列 */
    int upsertRebate(ActionRebateConfigPO actionRebateConfigPO);
}
