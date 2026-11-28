package com.betterschool.domain.draw.service.recon;

import com.betterschool.domain.draw.model.valobj.AwardPrereserveConservationVO;
import com.betterschool.domain.draw.model.valobj.RecordMissingDeductVO;
import com.betterschool.domain.draw.model.valobj.UnmatchedDeductDetailVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 抽奖库存全量对账轮（drawReconScan）的只读查询服务：不变量的聚合读数 / 差异行清单。
 * <p>
 * 对账不变量属于抽奖域的读模型（账户分页、B3、正反向配对），因此查询入口留在这里；
 * 实现在 infrastructure，库存侧口径（biz_type 等）由实现补齐。case 只做编排，不直接读 DAO。
 */
public interface IDrawReconQueryService {

    List<String> listLedgerAwardIds(String cursorAwardId, int limit);

    List<AwardPrereserveConservationVO> queryPrereserveConservation(LocalDateTime before, List<String> awardIds);

    List<UnmatchedDeductDetailVO> listUnmatchedDeductDetails(LocalDateTime before, List<String> awardIds);

    List<RecordMissingDeductVO> listRecordsMissingDeduct(LocalDateTime before, List<String> awardIds);
}
