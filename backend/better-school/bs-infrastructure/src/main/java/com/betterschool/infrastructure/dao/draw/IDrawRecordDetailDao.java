package com.betterschool.infrastructure.dao.draw;

import com.betterschool.infrastructure.dao.draw.po.DrawRecordDetailPO;
import com.betterschool.infrastructure.dao.draw.po.ExchangeDetailRowPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 抽奖动作明细表（draw_record_detail）Mapper：一表一 DAO。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/draw/DrawRecordDetailMapper.xml。
 */
@Mapper
public interface IDrawRecordDetailDao {

    /** 撞 uk_record_type 返回 0 = 该记录该动作已落过（state 推进重试幂等） */
    int insertIgnoreRecordDetail(DrawRecordDetailPO drawRecordDetailPO);

    /** 兑换动作明细批量读：draw_record_detail 单表（type=EXCHANGE，recordId → createTime） */
    List<ExchangeDetailRowPO> selectExchangeDetailsByRecordIds(@Param("recordIds") List<String> recordIds);
}
