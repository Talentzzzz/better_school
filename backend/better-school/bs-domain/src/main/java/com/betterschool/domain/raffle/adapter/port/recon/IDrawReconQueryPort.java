package com.betterschool.domain.raffle.adapter.port.recon;

import com.betterschool.domain.raffle.model.valobj.InventoryLedgerConservationVO;
import com.betterschool.domain.raffle.model.valobj.InventoryPrereserveConservationVO;
import com.betterschool.domain.raffle.model.valobj.RecordMissingDeductVO;
import com.betterschool.domain.raffle.model.valobj.UnmatchedDeductDetailVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 抽奖库存全量对账轮（drawReconScan）的只读查询端口：四条不变量的聚合读数 / 差异行清单。
 * 纯旁路——只读聚合，不进合并器、不写任何业务表；公式出处见各返回值对象 javadoc
 * （B1 台账守恒 / B3 预扣守恒 / 正向配对 / 反向配对）。
 * <p>
 * 四条不变量按台账账户（{@code draw_strategy_award_inventory}）分批跑（spec US14 低峰不与业务争抢）：
 * 先经 {@link #listLedgerAwardIds} 按 award_id 游标分页取账户批，再把整批 {@code awardIds}
 * 传给四条查询做 IN 过滤——B1/B3 与正向配对按 {@code award_id} 过滤，
 * 反向配对按 {@code inv_id} 过滤（发放来源快照口径不变）。
 * <p>
 * 实现在 infrastructure（抽奖域不认识库存表列），{@code biz_type} 等库存侧口径由端口实现填。
 * 数据窗口统一 {@code update_time < before}（{@code before = now - 10min}，与孤儿种单阈值对齐，
 * 避开主链路在途窗口；边界假差错由差错 PENDING → 下一轮复查确认消化）。
 */
public interface IDrawReconQueryPort {

    /**
     * 台账账户 award_id 分页：按 {@code award_id} 升序、游标 {@code award_id > cursorAwardId}
     * 取下一批（首批游标传空串，上限 {@code limit}）——四条不变量按账户分批跑的分页读入口。
     */
    List<String> listLedgerAwardIds(String cursorAwardId, int limit);

    /** B1 台账守恒：批内账户（award_id IN）按行返回台账三列与 Σ(flow_type=0 分配转入) 两侧读数，差异判断在域侧 */
    List<InventoryLedgerConservationVO> queryLedgerConservation(LocalDateTime before, List<String> awardIds);

    /** B3 预扣守恒：批内账户（award_id IN）按行返回台账 waiting+issued 与 Σ(flow_type=1 预扣明细) 两侧读数 */
    List<InventoryPrereserveConservationVO> queryPrereserveConservation(LocalDateTime before, List<String> awardIds);

    /** 正向配对：批内账户（award_id IN）预扣明细的 biz_id 既不命中 draw_record 也不命中孤儿对账单 → 漏结候选行清单 */
    List<UnmatchedDeductDetailVO> listUnmatchedDeductDetails(LocalDateTime before, List<String> awardIds);

    /** 反向配对：批内账户（inv_id IN，发放来源快照口径不变）中奖记录无对应预扣明细 → 数据破坏级缺失候选行清单 */
    List<RecordMissingDeductVO> listRecordsMissingDeduct(LocalDateTime before, List<String> awardIds);
}
