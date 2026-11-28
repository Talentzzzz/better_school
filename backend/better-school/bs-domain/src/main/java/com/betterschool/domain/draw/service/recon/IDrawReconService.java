package com.betterschool.domain.draw.service.recon;

import com.betterschool.domain.draw.model.entity.DrawOrphanPrereserveOrderEntity;
import com.betterschool.domain.draw.model.entity.DrawReconVarianceEntity;
import com.betterschool.domain.draw.model.entity.DrawRecordEntity;
import com.betterschool.types.draw.DrawReconVarianceStateEnum;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 抽奖库存对账写面（孤儿预扣收敛 + 每日全量对账轮）：{@link IDrawReconQueryService} 是只读取数口，
 * 本服务承接对账单状态机与对账轮 / 差错表的写入。
 * <p>
 * 与读侧同理，case 只经本服务取写能力，不直连 {@code IDrawRepository}；域内判据（孤儿状态机、
 * 差错登记幂等）与仓储事务边界都留在这里。跨域动作（奖品库存回补、次数退还、抽奖重放）
 * 仍由 case 层顺序编排。
 */
public interface IDrawReconService {

    // ---------- 孤儿预扣对账单 ----------

    /** 按抽奖记录反查扣次明细的用户 ID；扣次明细不在（数据破坏）返回 null */
    String queryUserIdByDrawCountRef(String refId);

    /**
     * 种一张孤儿预扣对账单（INSERT IGNORE，撞 uk_record_id 幂等跳过）。
     *
     * @return true = 本周期真的种出了（新孤儿）
     */
    boolean seedOrphanPrereserveOrder(String recordId, String awardId, String userId, Long quantity);

    /** 到期待收敛的对账单（state=ORPHAN 且到点），按 id 升序 FIFO */
    List<DrawOrphanPrereserveOrderEntity> listDueOrphanPrereserveOrders(int limit);

    /** 抢占式认领一张待收敛单（递增 retry_count 并写退避时点）；false = 被抢先 / 状态漂移 */
    boolean claimOrphanPrereserveOrder(Long id, LocalDateTime nextRetryAt);

    /** 重试耗尽置 DEAD（条件更新 state=ORPHAN） */
    void markOrphanPrereserveOrderDead(Long id);

    /** 按抽奖记录业务 ID 读记录；不存在返回 null */
    DrawRecordEntity queryRecordByRecordId(String recordId);

    /** 收敛完成置 DONE（条件更新 state<>DONE，幂等） */
    boolean finishOrphanPrereserveOrder(String recordId);

    /** 按抽奖记录业务 ID 读孤儿对账单；不存在返回 null */
    DrawOrphanPrereserveOrderEntity queryOrphanPrereserveOrderByRecordId(String recordId);

    /** 死信单点名扫描：state=DEAD，按 id 升序，上限 limit */
    List<DrawOrphanPrereserveOrderEntity> listDeadOrphanPrereserveOrders(int limit);

    // ---------- 对账轮 / 差错表 ----------

    /** 起轮：INSERT IGNORE（撞 uk_round_no 幂等跳过），扫描开始时间取当前时刻 */
    void startReconRound(String roundNo);

    /** 登记差错（INSERT IGNORE，撞 uk_round_check_biz 幂等跳过）；false = 本轮已登记过 */
    boolean registerReconVariance(DrawReconVarianceEntity drawReconVarianceEntity);

    /** 全部轮次的 PENDING 差错（二次核对输入），按 id 升序 */
    List<DrawReconVarianceEntity> listPendingReconVariances();

    /** 复查消失（日切边界假差错）：PENDING → FALSE_ALARM */
    void markReconVarianceFalseAlarm(Long id);

    /** 二次核对确认：PENDING → CONFIRMED；false = 已不在 PENDING */
    boolean confirmReconVariance(Long id);

    /** 平账分流·自动修复：CONFIRMED → AUTO_FIXED */
    void markReconVarianceAutoFixed(Long id);

    /** 该轮次登记差错总数 */
    long countReconVariancesByRound(String roundNo);

    /** 该轮次指定状态的差错数 */
    long countReconVariancesByRoundAndState(String roundNo, DrawReconVarianceStateEnum drawReconVarianceState);

    /** 终轮：按 round_no 回填结束时间（当前时刻）与三项计数 */
    void finishReconRound(String roundNo, int varianceTotal, int confirmedTotal, int autoFixedTotal);
}
