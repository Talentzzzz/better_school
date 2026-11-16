package com.betterschool.marketing.domain.drawcount.adapter.repository;

import com.betterschool.marketing.domain.drawcount.model.entity.DrawCountBucketEntity;
import com.betterschool.marketing.domain.drawcount.model.entity.DrawCountDetailEntity;
import com.betterschool.marketing.types.drawcount.DrawCountReasonType;

import java.util.List;

/**
 * 抽奖次数仓储：桶模型（draw_count_bucket + draw_count_detail）的数据访问面（一域一仓储），
 * 与积分侧同构（入账幂等键 uk_user_reason_ref、消费流水先行、FIFO 加行锁）。
 * <p>
 * 事务：一次入账与一次扣次各由仓储自己用 {@code TransactionTemplate}（REQUIRED）收成一个原子用例；
 * 域服务不再注入 {@code TransactionTemplate}。带 {@code ForUpdate} 语义的读在仓储方法内部完成。
 */
public interface IDrawCountRepository {

    /**
     * 一次入账，**同一事务**内完成：造桶（bucket_id 走统一 IdGenerator 发号（uuid-biz-id 票 07）、expire_at = 事务内时点 + 次数有效期）
     * → 插桶（撞 {@code uk_user_reason_ref} 即幂等命中）。
     * 幂等键落在 uk_user_reason_ref 上，重复入账不重复加次数；「幂等跳过」的日志由域服务按业务口径打。
     * <p>
     * 时点与有效期都在事务内取：时间戳口径与改造前一致（同票 03 {@code createOrderWithLock} 先例）。
     *
     * @param reasonType REDEEM（兑换入账）或 REBATE（行为返利）
     * @return true = 本次真的入账了；false = 这笔入账已记过（幂等跳过）
     */
    boolean creditBucket(String userId, int amount, DrawCountReasonType reasonType, String refId);

    /**
     * 一次抽奖扣次，**同一事务**内完成：幂等预检（该抽奖记录已扣过即跳过）→ FIFO 取最早可用桶 →
     * 落一行明细并把该桶 remain 减 1。桶不足返回 false，一行都没动（无半扣）。
     */
    boolean deductForDraw(String userId, String recordId);

    /** 是否已有该抽奖记录的消费明细（加锁读幂等预检）：重放快路径，真正的互斥是明细撞 uk 后回滚 */
    boolean existsDetailForUpdate(String refId);

    /** 当前可用桶（{@code remain > 0 AND expire_at > NOW()}），按 FIFO（expire_at, id）加行锁 */
    List<DrawCountBucketEntity> listAvailableBucketsForUpdate(String userId);

    /**
     * 落一行消费明细并把该桶 remain 减 1（流水先行：先 INSERT IGNORE 明细、撞 uk 则整行不动）。
     * 返回 false = 该桶这一笔抽奖已经扣过。本方法会被外层用例与测试直接复用，职责保持单一只管桶与明细。
     */
    boolean consumeBucket(DrawCountDetailEntity detail);

    /**
     * 剩余次数 = 未过期且未扣完的桶 remain 之和（没有余额列）。
     * 对外读与消费路径同口径。
     */
    int sumAvailableRemain(String userId);

    /**
     * 懒过期清理（定时任务调度）：把已过期且仍有余量的桶 remain 置 0（不删行、留审计），
     * 返回清理桶行数。过期判定以读路径 {@code expire_at > NOW()} 为准，本任务只是账务收拾。
     */
    int expireBuckets();
}
