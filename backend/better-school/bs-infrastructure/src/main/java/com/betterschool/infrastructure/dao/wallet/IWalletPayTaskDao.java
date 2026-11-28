package com.betterschool.infrastructure.dao.wallet;

import com.betterschool.infrastructure.dao.wallet.po.WalletPayTaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付回调任务表（{@code wallet_pay_task}）Mapper：一表一 DAO。
 * SQL 定义在 app 模块 {@code resources/mybatis/mapper/WalletPayTaskMapper.xml}。
 * <p>
 * 这是域内最终一致 outbox，不是 MQ outbox：行由钱包支付事务（{@code WalletRepository.pay}）
 * 同事务写入，事务提交后进程内回调商户并置 DONE，失败留下的行由 {@code productPayTaskScan}
 * 进程内重放（ADR-0017 / ADR-0018）。
 */
@Mapper
public interface IWalletPayTaskDao {

    /** 落任务（INSERT IGNORE）：uk_order_id 冲突 = 已有任务行，重复支付 / 重试安全（不覆盖状态） */
    int insertIgnore(WalletPayTaskPO walletPayTaskPO);

    /** 到期待回调：state=0 且 next_retry_at 为空或已到点，按 id 升序（走 idx_state_next_retry） */
    List<WalletPayTaskPO> listDue(@Param("limit") int limit);

    /** 抢占式认领：state=0 且到点才生效，递增 retry_count 并写退避时点；0 行 = 被抢 / 状态漂移 */
    int claim(@Param("id") Long id, @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /** 置 DONE：未完成才生效（重复标记幂等；死信被成功回调覆盖时也收敛） */
    int markDone(@Param("orderId") String orderId);

    /** 置 DEAD：state=0 才生效；重试耗尽后人工重置回 PENDING 可自愈 */
    int markDead(@Param("id") Long id);
}
