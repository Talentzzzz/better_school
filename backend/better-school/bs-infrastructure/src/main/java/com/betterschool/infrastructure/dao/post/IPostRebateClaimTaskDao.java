package com.betterschool.infrastructure.dao.post;

import com.betterschool.infrastructure.dao.post.po.PostRebateClaimTaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发帖返利领取任务表（{@code post_rebate_claim_task}）Mapper：一表一 DAO。
 * SQL 定义在 app 模块 {@code resources/mybatis/mapper/PostRebateClaimTaskMapper.xml}。
 * <p>
 * 这是域内最终一致任务表，不是 MQ outbox：行由请求线程同步领取后置 DONE，
 * 失败留 PENDING 由 {@code postRebateClaimScan} 进程内重放（ADR-0017）。
 */
@Mapper
public interface IPostRebateClaimTaskDao {

    /** 落任务：与发帖行同事务；撞 uk_post_id 是重复落库的信号（正常路径不会发生） */
    int insert(PostRebateClaimTaskPO postRebateClaimTaskPO);

    /** 到期待领取：state=0 且 next_retry_at 为空或已到点，按 id 升序（走 idx_state_next_retry） */
    List<PostRebateClaimTaskPO> listDue(@Param("limit") int limit);

    /** 抢占式认领：state=0 且到点才生效，递增 retry_count 并写退避时点；0 行 = 被抢 / 状态漂移 */
    int claim(@Param("id") Long id, @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /** 置 DONE：state=0 才生效（重复标记幂等） */
    int markDone(@Param("postId") String postId);

    /** 置 DEAD：state=0 才生效 */
    int markDead(@Param("id") Long id);
}
