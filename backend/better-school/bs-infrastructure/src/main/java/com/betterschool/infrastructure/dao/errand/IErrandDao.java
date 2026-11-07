package com.betterschool.infrastructure.dao.errand;

import com.betterschool.domain.errand.model.valobj.ErrandFeedCursorVO;
import com.betterschool.domain.errand.model.valobj.ErrandFeedIndexItemVO;
import com.betterschool.infrastructure.dao.errand.po.ErrandFeedIndexRow;
import com.betterschool.infrastructure.dao.errand.po.ErrandOrderEvent;
import com.betterschool.infrastructure.dao.errand.po.ErrandOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 跑腿订单 Mapper：SQL 定义在 app 模块 resources/mybatis/mapper/ErrandMapper.xml
 */
@Mapper
public interface IErrandDao {

    int insert(ErrandOrder order);

    /** 详情内容查询：纯 errand_order 单表（昵称头像与浏览 UV 仓储回查拼装，SQL 层零 JOIN） */
    ErrandOrder selectById(@Param("orderId") String orderId);

    /** 按 id 列表查询（不分状态，搜索同步反查/搜索结果组装用） */
    List<ErrandOrder> selectByIds(@Param("ids") List<String> orderIds);

    /** 浏览 UV 计数窄表批量读：按 id 集单表 IN 查询，仓储补 0 拼装（详情 UV 回填与列表展示共用） */
    List<ErrandOrder> selectViewCountsByOrderIds(@Param("orderIds") List<String> orderIds);

    /**
     * 搜索索引全量重建的游标页（uuid-biz-id 票 05）：业务 ID 是 UUID 字符串，按 errand_order_id
     * 字典序推进（同一排序规则下 &gt; 与 ORDER BY 自洽），不再按物理 id
     */
    List<String> selectIdsAfter(@Param("lastId") String lastId, @Param("limit") int limit);

    /** 旧直查降级路径 price/time 三档：公开口径 SQL 分页（hot 档走 {@link #selectVisibleRows} 内存排序） */
    List<ErrandOrder> selectPage(@Param("type") String type, @Param("state") String state,
                                   @Param("sort") String sort,
                                   @Param("offset") int offset, @Param("limit") int limit);

    /** hot 档降级取数：公开口径全集（筛选条件同 {@link #selectPage}，无 LIMIT），窄表批量回查后仓储内存排序切片 */
    List<ErrandOrder> selectVisibleRows(@Param("type") String type, @Param("state") String state);

    long count(@Param("type") String type, @Param("state") String state);

    /**
     * 跑腿大厅索引序扫描（market-feed-uv 票 04）：只取 PUBLISHED（行常驻 + state 快照），
     * 排序键 / 游标谓词 / 排序方向随 sort 分支，走 (state, 排序键, errand_order_id) 索引的 range scan
     * （热度档为 (state, view_count, create_time, errand_order_id)，uuid-biz-id 票 05）。
     * 部分列直映域 VO（游标锚点要排序键随行带出，热度快照供大厅展示浏览量）
     */
    List<ErrandFeedIndexItemVO> selectFeedIndex(@Param("sort") String sort,
                                                @Param("cursor") ErrandFeedCursorVO cursor,
                                                @Param("limit") int limit);

    int payById(@Param("orderId") String orderId, @Param("timeLimitHours") int timeLimitHours);

    int grab(@Param("orderId") String orderId, @Param("takerId") String takerId);

    /** 抢单后的当前轮次（同事务内读，锁单超时注册的复合 bizId 要带上它） */
    int selectLockSeqById(@Param("orderId") String orderId);

    /** 锁单超时释放（轮次守卫）：LOCKED → PUBLISHED 清 taker_id；lock_seq 挡住陈旧轮次回调 */
    int releaseLockedByTimeout(@Param("orderId") String orderId, @Param("lockSeq") int lockSeq);

    /** 确认接单：LOCKED → CONFIRMED（仅持单人；不改 lock_seq） */
    int acceptLockedById(@Param("orderId") String orderId, @Param("takerId") String takerId);

    /** 放弃：LOCKED → PUBLISHED（仅持单人；清 taker_id，不动 taker_canceled / lock_seq） */
    int abandonLockedById(@Param("orderId") String orderId, @Param("takerId") String takerId);

    /** 窗口内主动放弃次数（actor_id + LOCK_ABANDONED + create_time >= since） */
    int countAbandonsSince(@Param("actorId") String actorId, @Param("since") LocalDateTime since);

    /** 窗口内最近一次主动放弃时刻（无则 null） */
    LocalDateTime selectLatestAbandonTimeSince(@Param("actorId") String actorId, @Param("since") LocalDateTime since);

    int pickupById(@Param("orderId") String orderId, @Param("takerId") String takerId);

    int deliverById(@Param("orderId") String orderId, @Param("takerId") String takerId);

    int confirmById(@Param("orderId") String orderId, @Param("publisherId") String publisherId);

    int cancelByPublisher(@Param("orderId") String orderId, @Param("publisherId") String publisherId);

    int cancelByTaker(@Param("orderId") String orderId, @Param("takerId") String takerId);

    int disputeById(@Param("orderId") String orderId);

    int arbitrateCancelById(@Param("orderId") String orderId);

    int arbitrateCompleteById(@Param("orderId") String orderId);

    int markTimeout(@Param("orderId") String orderId);

    /** 待支付超时取消：PENDING_PAYMENT → CANCELLED（未冻结无退款） */
    int cancelPendingPayment(@Param("orderId") String orderId);

    /** 草稿全量编辑（仅 DRAFT 行生效；作者与状态位不在 SET 列内） */
    int updateDraft(ErrandOrder order);

    /** 提交草稿：DRAFT → PENDING_PAYMENT（条件 UPDATE，带作者守卫） */
    int submitDraft(@Param("orderId") String orderId, @Param("publisherId") String publisherId);

    /** 删除草稿：软删 DRAFT → DELETED（条件 UPDATE，带作者守卫） */
    int softDelete(@Param("orderId") String orderId, @Param("publisherId") String publisherId);

    /** 待支付超时订单 id 列表（PENDING_PAYMENT 且进入待支付早于 before：直接发布=落库时刻，草稿提交=提交时刻） */
    List<String> selectPayTimeoutOrderIds(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /** 订单超时 id 列表（PUBLISHED/LOCKED/CONFIRMED/PICKED_UP 且 deadline 已过） */
    List<String> selectTimeoutOrderIds(@Param("limit") int limit);

    /** 待审超时 id 列表（PENDING_REVIEW 且进入待审早于 before，24h 提醒扫描用） */
    List<String> selectPendingReviewTimeoutOrderIds(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /** 审核通过：PENDING_REVIEW → PENDING_PAYMENT，返回影响行数（1=本次刚通过） */
    int reviewApproveById(@Param("orderId") String orderId);

    /** 审核驳回：PENDING_REVIEW → CANCELLED，返回影响行数 */
    int reviewRejectById(@Param("orderId") String orderId);

    /** 送达超时订单 id 列表（DELIVERED 且送达早于 before，24h 自动完成扫描用） */
    List<String> selectAutoCompleteOrderIds(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /** 送达后自动完成：DELIVERED → COMPLETED（发布方 24h 未确认） */
    int autoCompleteById(@Param("orderId") String orderId);

    List<ErrandOrder> selectMyPublished(@Param("userId") String userId, @Param("state") String state,
                                          @Param("offset") int offset, @Param("limit") int limit);

    long countMyPublished(@Param("userId") String userId, @Param("state") String state);

    /** 某用户发布的进行中跑腿单分页（作者主页「跑腿」tab，访客口径）：publisher_id 且 state IN ('PUBLISHED','LOCKED','CONFIRMED') */
    List<ErrandOrder> selectPublicPageByPublisher(@Param("userId") String userId,
                                                    @Param("offset") int offset, @Param("limit") int limit);

    long countPublicByPublisher(@Param("userId") String userId);

    List<ErrandOrder> selectMyTaken(@Param("userId") String userId, @Param("state") String state,
                                      @Param("offset") int offset, @Param("limit") int limit);

    long countMyTaken(@Param("userId") String userId, @Param("state") String state);

    /** 订单状态流转事件落表（同事务，只插入） */
    int insertOrderEvent(ErrandOrderEvent event);

    // ── 跑腿浏览 UV 三步（market-feed-uv 票 02，照帖子 user_view_history / post_view_count 先例）──
    // 一组一个事务：INSERT IGNORE 明细（影响行数 = 真正新增的 UV 数）→ 全组刷 viewed_at → 加速列 += 新增数

    /** 第一步：UV 明细批量写，(user_id, errand_order_id) 主键撞键裁决重复浏览 */
    int insertOrderViewHistory(@Param("orderId") String orderId, @Param("userIds") List<String> userIds);

    /** 第二步：刷新本组全部键的浏览时刻（含刚插入的行）——重复浏览的唯一可见效果 */
    int touchOrderViewedAt(@Param("orderId") String orderId, @Param("userIds") List<String> userIds);

    /** 第三步：加速列 upsert（行懒建 ODKU）；增量 = insertOrderViewHistory 的影响行数 */
    int upsertOrderViewCount(@Param("orderId") String orderId, @Param("delta") int delta);

    /** 订单当前的浏览 UV 数（加速列 errand_view_count；计数行不存在返回 null，调用方按 0 收） */
    Integer selectViewCount(@Param("orderId") String orderId);

    /** 当前用户浏览过的订单 id 集合（大厅已读灰显批量回显，走 (user_id, errand_order_id) 主键前缀） */
    List<String> selectViewedOrderIds(@Param("userId") String userId, @Param("orderIds") List<String> orderIds);

    // ── feed 异构索引同事务直写（market-feed-uv 票 03：行常驻 + state 快照，审核通过行诞生，
    //    此后 13 态流转点旁带同步，照帖子 post_feed_index 先例）──

    /** 审核通过：行诞生（INSERT...SELECT 取 reward/create_time 快照；ODKU 只翻 state——跑腿无重提再过审） */
    int upsertFeedIndexOnApprove(@Param("orderId") String orderId);

    /** 状态快照同步：镜像 errand_order 的新状态（大厅可见 = PUBLISHED，读路径按快照挑状态） */
    int updateFeedIndexState(@Param("orderId") String orderId, @Param("state") String state);

    /** 浏览合并事务旁路 ±N：纯 UPDATE 不 upsert；0 行 = 索引行缺失，调用方落 warn 交重建 job */
    int updateFeedIndexViewCount(@Param("orderId") String orderId, @Param("delta") int delta);

    // ── feed 索引全量重建（market-feed-uv 票 06：兜底恢复手段，照帖子 IPostDao 同名先例）──
    // 各步由仓储收进一个事务（执行序：计数校准 → 清空索引 → 拼装重灌）：
    // 先算准计数窄表再抄进重灌行集，读者要么看到重建前、要么看到重建后的完整索引

    /** 清空索引表（DELETE 不用 TRUNCATE——TRUNCATE 隐式提交，会毁掉与重灌语句的原子性） */
    int clearFeedIndex();

    /** 重灌取数①：errand_order 全量 (errand_order_id, state, create_time, reward)——行集 = PUBLISHED ∪ 有过审批通过，仓储拼装 */
    List<ErrandFeedIndexRow> selectRebuildRows();

    /** 重灌写回：仓储拼装好的行集批量 INSERT（feed_time = MAX(APPROVE) 兜底 create_time；view_count 抄计数窄表） */
    int insertFeedIndexRows(@Param("rows") List<ErrandFeedIndexRow> rows);

    /** 浏览 UV 加速列校准收尾：清掉没有明细行的孤儿计数行（重投影由 {@link #recalibrateViewCounts} 完成） */
    int deleteViewCountsMissingHistory();

    /** 浏览 UV 加速列校准：errand_view_count 重投影 = errand_view_history 行数（ODKU 补缺行 / 刷漂移 / 幂等落回同值） */
    int recalibrateViewCounts();
}
