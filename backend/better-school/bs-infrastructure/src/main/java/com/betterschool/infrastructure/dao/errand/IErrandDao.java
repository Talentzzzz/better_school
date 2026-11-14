package com.betterschool.infrastructure.dao.errand;

import com.betterschool.infrastructure.dao.errand.po.ErrandFeedIndexRow;
import com.betterschool.infrastructure.dao.errand.po.ErrandOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 跑腿订单 Mapper（errand_order 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/ErrandMapper.xml
 * （事件 / feed 索引 / 浏览明细 / 计数窄表拆分至 IErrandOrderEventDao / IErrandFeedIndexDao /
 * IErrandViewHistoryDao / IErrandViewCountDao 及同名 Mapper XML）
 */
@Mapper
public interface IErrandDao {

    int insert(ErrandOrder order);

    /** 详情内容查询：纯 errand_order 单表（昵称头像与浏览 UV 仓储回查拼装，SQL 层零 JOIN） */
    ErrandOrder selectById(@Param("orderId") String orderId);

    /** 按 id 列表查询（不分状态，搜索同步反查/搜索结果组装用） */
    List<ErrandOrder> selectByIds(@Param("ids") List<String> orderIdList);

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

    /** 支付确认（deadline 由用户指定随单落库，支付只翻状态；WHERE 带 deadline &gt; NOW() 防过期单支付） */
    int payById(@Param("orderId") String orderId);

    /** 截止时间合法性（数据库时钟为事实源）：now+15min ~ now+7d 之间返回 true */
    boolean checkDeadlineLegal(@Param("deadline") LocalDateTime deadline);

    int grab(@Param("orderId") String orderId, @Param("takerId") String takerId);

    /** 抢单后的当前轮次（同事务内读，锁单超时注册的复合 bizId 要带上它） */
    int selectLockSeqById(@Param("orderId") String orderId);

    /** 锁单超时释放（轮次守卫）：LOCKED → PUBLISHED 清 taker_id；lock_seq 挡住陈旧轮次回调 */
    int releaseLockedByTimeout(@Param("orderId") String orderId, @Param("lockSeq") int lockSeq);

    /** 确认接单：LOCKED → CONFIRMED（仅持单人；不改 lock_seq） */
    int acceptLockedById(@Param("orderId") String orderId, @Param("takerId") String takerId);

    /** 放弃：LOCKED → PUBLISHED（仅持单人；清 taker_id，不落 TAKER_CANCELLED 事件、不改 lock_seq） */
    int abandonLockedById(@Param("orderId") String orderId, @Param("takerId") String takerId);

    /** 事件序号取号（写一半）：订单行计数器 +1（事件写入事务内先动订单行持行锁，同订单串行发号） */
    int bumpEventSeq(@Param("orderId") String orderId);

    /** 事件序号取号（读一半）：同事务内读，得本次事件落表用的 event_seq */
    int selectEventSeqById(@Param("orderId") String orderId);

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

    // ── feed 索引全量重建（market-feed-uv 票 06）：重灌取数①（清空/写回在 IErrandFeedIndexDao）──

    /** 重灌取数①：errand_order 全量 (errand_order_id, state, create_time, reward)——行集 = PUBLISHED，仓储拼装 */
    List<ErrandFeedIndexRow> selectRebuildRows();
}
