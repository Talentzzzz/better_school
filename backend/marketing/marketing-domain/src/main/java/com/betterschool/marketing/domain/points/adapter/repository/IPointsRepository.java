package com.betterschool.marketing.domain.points.adapter.repository;

import com.betterschool.marketing.domain.points.model.valobj.PointsDeductResultVO;
import com.betterschool.marketing.domain.points.model.valobj.PointsTransactionVO;
import com.betterschool.marketing.types.points.PointsReasonType;

import java.util.List;

/**
 * 积分仓储：桶模型（points_bucket + points_detail）的数据访问面（一域一仓储）。
 * <p>
 * 事务：一次入账（桶 + 变动事件 outbox 同事务）与一次消费（幂等预检 + 读可用桶 + 算方案 + 照方案落库 + 变动事件）
 * 各由仓储自己用 {@code TransactionTemplate}（REQUIRED）收成一个原子用例；域服务不再注入
 * {@code TransactionTemplate}。消费的内部步骤——幂等预检、加锁读可用桶、写明细、改桶余量、发变动事件——
 * 都不在这张端口上：它们是 {@link #deduct}（入账侧则是 {@link #insertBucketWithChanged}）的内部步骤，
 * 端口只留「一次消费」「一次入账」这样的完整动作。
 */
public interface IPointsRepository {

    /**
     * 一次入账，**同一事务**内完成：造桶（bucket_id 走统一 IdGenerator 发号（uuid-biz-id 票 07）、expire_at = 事务内时点 + 积分有效期）
     * → 插桶（撞 {@code uk_user_reason_ref} 即幂等命中）→ 读变动后余额 →
     * 落积分变动事件（走本服务 outbox）。
     * 桶已存在时不读余额、不发事件、也不打日志，返回 false——「幂等跳过」的日志由域服务按业务口径打。
     * <p>
     * 时点与有效期都在事务内取：时间戳口径与改造前一致（同票 03 `createOrderWithLock` 的
     * {@code payTimeoutMinutes} 先例）。
     *
     * @return true = 本次真的入了账；false = 这笔入账已记过（幂等跳过）
     */
    boolean insertBucketWithChanged(String userId, int amount, PointsReasonType reasonType, String refId);

    /**
     * 一次消费（兑抽奖次数 / 兑奖品），**同一事务**内完成：幂等预检（已扣过即按成功返回）→
     * 加锁读出该用户全部可用桶 → 内存里算出这笔方案（{@link com.betterschool.marketing.domain.points.model.valobj.PointsDeductPlanVO}）
     * → 照方案落库（明细先行，至多三条语句，条数与桶数无关）→ 读变动后余额 → 落积分变动事件。
     * <p>
     * 余额不足返回 {@code insufficient}（一行都没动，事务空提交——不用回滚表达业务失败）；
     * 并发重复兑换在明细撞唯一键（表现为批量写明细的影响行数偏少）时整事务回滚，
     * 由本方法在事务外重读真实余额按幂等成功返回。
     */
    PointsDeductResultVO deduct(String userId, int amount, PointsReasonType reasonType, String refId);

    /**
     * 余额 = 未过期且未扣完的桶 remain 之和（没有余额列）。
     * 对外余额读与消费路径的不足判断、变动事件 balanceAfter 同口径。
     */
    int sumAvailableRemain(String userId);

    /**
     * 桶合并（points-compaction 票，定时任务调度）：把同用户的「有效小额桶」（remain &le; 阈值且
     * 数量 &ge; 2）并成一桶——新桶 amount = remain = SUM、expire_at = 合并集合最大值（把小桶有效期
     * 延长到其中最晚到期，产品口径），旧桶全部置 0（不删行，发放流水留审计）。
     * 单事务逐用户推进，加锁重读 + 影响行数守卫，SUM(remain) 合并前后守恒、用户无感。
     *
     * @return 本轮实际合并（清零）的旧桶总数
     */
    int compactSmallBuckets();

    /** 流水（入账 ∪ 消费按时间归并），create_time DESC */
    List<PointsTransactionVO> listTransactions(String userId, int offset, int limit);

    /** 流水总条数（两表相加） */
    long countTransactions(String userId);

    /**
     * 积分有效期天数（{@code points_validity_config} 单行，种子 id=1 / 365 天）。
     * 本域自己的配置表——入账算 expire_at 的那次读就在入账事务内，不走端口。
     * 缺行抛系统异常（种子或迁移没跑）。
     */
    int validDays();

    /** 更新积分有效期天数（单行，id=1）；缺行视为系统错误 */
    void updateValidDays(int days);
}
