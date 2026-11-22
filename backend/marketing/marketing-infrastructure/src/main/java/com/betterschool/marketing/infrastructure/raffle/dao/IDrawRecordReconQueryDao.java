package com.betterschool.marketing.infrastructure.raffle.dao;

import com.betterschool.marketing.infrastructure.raffle.dao.po.RecordMissingDeductPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对账轮（drawReconScan）只读聚合 Mapper——{@code draw_record} 侧的反向配对查询。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/raffle/DrawRecordReconQueryMapper.xml。
 * 纯旁路：只读聚合，不写任何表；数据窗口 {@code update_time < before}。
 * 按台账账户分批跑：批内 {@code awardIds} 对 {@code inv_id} 做 IN 过滤
 * （inv_id 是发放来源快照，口径不变），IN 列表元素量 = 批大小（≤100，spec US14）。
 */
@Mapper
public interface IDrawRecordReconQueryDao {

    /**
     * 反向配对：批内账户（inv_id IN）窗口内中奖记录（{@code inv_id} 非空且非 THANKS）不存在对应
     * 预扣明细（{@code award_id = inv_id} 快照、{@code biz_id = record_id}，
     * {@code bizType} 由端口填 LOTTERY_DRAW）→ 数据破坏级缺失候选行清单（返回行即差异）。
     */
    List<RecordMissingDeductPO> listRecordsMissingDeduct(@Param("before") LocalDateTime before,
                                                         @Param("bizType") String bizType,
                                                         @Param("awardIds") List<String> awardIds);
}
