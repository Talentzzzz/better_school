package com.betterschool.infrastructure.dao.award;

import com.betterschool.infrastructure.dao.award.po.AwardPrereserveConservationPO;
import com.betterschool.infrastructure.dao.award.po.UnmatchedDeductDetailPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对账轮（drawReconScan）只读聚合 Mapper——奖品库存台账 / 明细侧的不变量查询与账户分页。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/award/AwardReconQueryMapper.xml。
 * 纯旁路：只读聚合，不写任何表、不进合并器；全部带数据窗口 {@code update_time < before}
 * （{@code before = now - 10min}，与孤儿种单阈值对齐）。
 * 三条查询按台账账户分批跑：批内 {@code awardIds} 做 IN 过滤（B3/正反向按 award_id），
 * IN 列表元素量 = 批大小（≤100，spec US14）。
 */
@Mapper
public interface IAwardReconQueryDao {

    /**
     * 台账账户 award_id 分页：按 {@code award_id} 升序、游标 {@code award_id > cursor} 取下一批
     * （首批游标传空串，上限 {@code limit}）——不变量按账户分批跑的分页读入口。
     */
    List<String> listLedgerAwardIds(@Param("cursor") String cursor, @Param("limit") int limit);

    /**
     * B3 预扣守恒：批内账户（award_id IN）按 {@code award_id} 一行返回台账 {@code waiting + issued} 与
     * Σ(flow_type = 1 预扣明细) 两侧读数（守恒判据 B3，出处见 mapper XML 注释）。
     */
    List<AwardPrereserveConservationPO> queryPrereserveConservation(@Param("before") LocalDateTime before,
                                                                        @Param("awardIds") List<String> awardIds);

    /**
     * 正向配对：批内账户（award_id IN）窗口内预扣明细（{@code bizType} 由端口填 LOTTERY_DRAW）的
     * {@code biz_id} 反查 {@code draw_record} 与孤儿对账单都未命中 → 漏结候选行清单（返回行即差异）。
     */
    List<UnmatchedDeductDetailPO> listUnmatchedDeductDetails(@Param("before") LocalDateTime before,
                                                             @Param("bizType") String bizType,
                                                             @Param("awardIds") List<String> awardIds);
}
