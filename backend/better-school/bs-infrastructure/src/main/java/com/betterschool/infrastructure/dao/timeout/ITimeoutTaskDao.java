package com.betterschool.infrastructure.dao.timeout;

import com.betterschool.infrastructure.dao.timeout.po.TimeoutTaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时任务 DAO。SQL 全部写逻辑表 {@code timeout_task}，物理分表由 ShardingSphere 的 hint 算法路由
 * ——**每条 SQL 都必须先经仓储层把槽位塞进 HintManager（库维 + 表维两个 hint 值）**，
 * 别在仓储之外直接调本接口：漏塞 hint 时写语句会被 ShardingSphere 的路由校验拒掉、
 * 读语句会退化成 16 表广播。
 * <p>
 * SQL 见 app 模块 {@code resources/mybatis/mapper/TimeoutTaskMapper.xml}。
 */
@Mapper
public interface ITimeoutTaskDao {

    /** 插一行任务；撞 uk_type_biz 抛 DuplicateKeyException（由仓储层翻成「已存在」） */
    int insert(TimeoutTaskPO timeoutTask);

    /** 按自然键取行；不存在返回 null */
    TimeoutTaskPO selectByKey(@Param("taskType") String taskType, @Param("bizId") String bizId);

    /** 终态行复活为 WAITING（条件 UPDATE）；返回影响行数，0 = 行不在终态（在途，幂等不改期） */
    int reviveTerminal(TimeoutTaskPO timeoutTask);

    /** 未终态行软置 CANCELED（条件 UPDATE）；返回影响行数，0 = 行不存在或已终态 */
    int cancelActive(@Param("taskType") String taskType, @Param("bizId") String bizId,
                     @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 扫描捞批：{@code status IN ('WAITING','RETRYING') AND next_fire_time <= dueBefore}，
     * 按 {@code next_fire_time} 升序取 {@code limit} 条。本语句没有分片键，路由必须靠仓储层塞的槽位 hint。
     */
    List<TimeoutTaskPO> selectDueBatch(@Param("dueBefore") LocalDateTime dueBefore, @Param("limit") int limit);

    /** 结算·成功桶：整桶一条 UPDATE 置 SUCCESS；带未终态守卫，返回实际改到的行数 */
    int markSuccessBatch(@Param("taskType") String taskType, @Param("bizIds") List<String> bizIds,
                         @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 结算·重试桶：整桶一条 UPDATE 置 RETRYING、{@code retry_count + 1}，
     * {@code next_fire_time} 按 {@code bizIds}/{@code nextFireTimes} 的**同一键序**走 CASE 逐行取；
     * 带未终态守卫，返回实际改到的行数。
     */
    int markRetryBatch(@Param("taskType") String taskType, @Param("bizIds") List<String> bizIds,
                       @Param("nextFireTimes") List<LocalDateTime> nextFireTimes,
                       @Param("updatedAt") LocalDateTime updatedAt);

    /** 结算·死信桶：整桶一条 UPDATE 置 FAILED、{@code retry_count + 1}；带未终态守卫 */
    int markDeadBatch(@Param("taskType") String taskType, @Param("bizIds") List<String> bizIds,
                      @Param("updatedAt") LocalDateTime updatedAt);

    /** 死信重发：只打 FAILED 行 → RETRYING、retry_count 清零、next_fire_time = 给定时刻 */
    int resendDeadLetter(@Param("taskType") String taskType, @Param("bizId") String bizId,
                         @Param("nextFireTime") LocalDateTime nextFireTime,
                         @Param("updatedAt") LocalDateTime updatedAt);

    /** 死信取消：只打 FAILED 行 → CANCELED */
    int cancelDeadLetter(@Param("taskType") String taskType, @Param("bizId") String bizId,
                         @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 死信列表的一页（管理面，票 06）：**全仓唯二不塞 hint 的语句**——按状态翻没有分片键，
     * 要让 ShardingSphere 广播 16 张分表再归并（塞 hint 会只查一张、翻不出全量死信）。
     * 只准经仓储的死信管理面方法调用，别复用到别的按键读上。
     */
    List<TimeoutTaskPO> selectDeadLetters(@Param("offset") int offset, @Param("limit") int limit);

    /** 死信总数（管理面翻页的 total）；同 {@link #selectDeadLetters} 不塞 hint，走广播 */
    long countDeadLetters();
}
