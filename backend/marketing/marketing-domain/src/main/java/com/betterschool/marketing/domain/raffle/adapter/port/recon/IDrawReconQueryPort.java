package com.betterschool.marketing.domain.raffle.adapter.port.recon;

import com.betterschool.marketing.domain.raffle.model.valobj.InventoryLedgerConservationVO;
import com.betterschool.marketing.domain.raffle.model.valobj.InventoryPrereserveConservationVO;
import com.betterschool.marketing.domain.raffle.model.valobj.RecordMissingDeductVO;
import com.betterschool.marketing.domain.raffle.model.valobj.UnmatchedDeductDetailVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 抽奖库存全量对账轮（drawReconScan）的只读查询端口：四条不变量的聚合读数 / 差异行清单。
 * 纯旁路——只读聚合，不进合并器、不写任何业务表；公式出处见各返回值对象 javadoc
 * （B1 台账守恒 / B3 预扣守恒 / 正向配对 / 反向配对）。
 * <p>
 * 实现在 infrastructure（抽奖域不认识库存表列），{@code biz_type} 等库存侧口径由端口实现填。
 * 数据窗口统一 {@code update_time < before}（{@code before = now - 10min}，与孤儿种单阈值对齐，
 * 避开主链路在途窗口；边界假差错由差错 PENDING → 下一轮复查确认消化）。
 */
public interface IDrawReconQueryPort {

    /** B1 台账守恒：按 award_id 返回台账三列与 Σ(flow_type=0 分配转入) 两侧读数，差异判断在域侧 */
    List<InventoryLedgerConservationVO> queryLedgerConservation(LocalDateTime before);

    /** B3 预扣守恒：按 award_id 返回台账 waiting+issued 与 Σ(flow_type=1 预扣明细) 两侧读数 */
    List<InventoryPrereserveConservationVO> queryPrereserveConservation(LocalDateTime before);

    /** 正向配对：预扣明细的 biz_id 既不命中 draw_record 也不命中孤儿对账单 → 漏结候选行清单 */
    List<UnmatchedDeductDetailVO> listUnmatchedDeductDetails(LocalDateTime before);

    /** 反向配对：中奖记录（有库存来源、非 THANKS）无对应预扣明细 → 数据破坏级缺失候选行清单 */
    List<RecordMissingDeductVO> listRecordsMissingDeduct(LocalDateTime before);
}
