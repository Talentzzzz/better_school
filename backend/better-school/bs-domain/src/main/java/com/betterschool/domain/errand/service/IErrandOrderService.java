package com.betterschool.domain.errand.service;

import com.betterschool.domain.errand.model.entity.ErrandOrderEntity;
import com.betterschool.domain.errand.model.valobj.ErrandArbitrateResult;
import com.betterschool.domain.errand.model.valobj.ErrandAutoCompleteResult;
import com.betterschool.domain.errand.model.valobj.ErrandCancelResult;
import com.betterschool.domain.errand.model.valobj.ErrandConfirmResult;
import com.betterschool.domain.errand.model.valobj.ErrandFeedPageVO;
import com.betterschool.domain.errand.model.valobj.ErrandLockTimeoutResult;
import com.betterschool.domain.errand.model.valobj.ErrandPayResult;
import com.betterschool.domain.errand.model.valobj.ErrandReviewResult;
import com.betterschool.domain.errand.model.valobj.ErrandTimeoutResult;

import java.math.BigDecimal;
import java.util.List;

/**
 * 跑腿订单服务：域内只做状态机与自身表写入，跨域动作（资金/信用分）通过结果对象返回，
 * 由 case 层在同一事务内编排执行（domain 不依赖其他域）
 */
public interface IErrandOrderService {

    /**
     * 创建订单（先付后审：同步初筛由 case 层前置，命中即业务异常不落库，通过则 PENDING_PAYMENT；
     * 信用分门槛由 case 层前置校验）。reward 为悬赏，支付时冻结
     */
    String createOrder(String userId, String type, String description, String pickupLocation,
                     String deliveryLocation, BigDecimal reward, List<String> images,
                     String timeLimit);

    /**
     * 创建草稿（草稿入口）：落 DRAFT，不付款、不对外、不注册支付超时；
     * 不做同步初筛与信用分门槛（提交时才校验），字段可缺省
     */
    String createDraft(String userId, String type, String description, String pickupLocation,
                     String deliveryLocation, BigDecimal reward, List<String> images,
                     String timeLimit);

    /** 编辑草稿（仅作者、仅 DRAFT）：全量覆盖内容字段，publisherId 与状态位不改、不产生状态迁移 */
    void updateDraft(String orderId, String userId, String type, String description, String pickupLocation,
                     String deliveryLocation, BigDecimal reward, List<String> images,
                     String timeLimit);

    /** 加载本人草稿（存在 + 作者 + DRAFT 三重守卫），提交前必填校验/同步初筛的输入源 */
    ErrandOrderEntity getOwnedDraft(String orderId, String userId);

    /** 提交草稿：DRAFT → PENDING_PAYMENT，同事务注册 15 分钟支付超时（必填 + 初筛 + 信用分由 case 层前置） */
    void submitDraft(String orderId, String userId);

    /** 删除草稿：软删 DRAFT → DELETED（仅作者、仅 DRAFT；本期 DELETED 仅从 DRAFT 可达） */
    void deleteDraft(String orderId, String userId);

    /** 审核通过（裁决用例 decide 编排）：PENDING_REVIEW → PUBLISHED，对外可见；已 PUBLISHED 幂等跳过；事件落 ADMIN 裁决 */
    void reviewApproveOrder(String orderId, String operatorId);

    /**
     * 审核拒绝（裁决用例 decide 编排，无事务）：PENDING_REVIEW → REJECTED 终态（打回不可见），
     * 返回解冻退款需求（冻结金额全额退回，流水幂等）；已 REJECTED 幂等重放结果
     */
    ErrandReviewResult reviewRejectOrder(String orderId, String operatorId, String reason);

    /** 确认支付（无事务）：payById 转 PENDING_REVIEW + bs-errand-paid outbox，返回冻结需求 */
    ErrandPayResult payOrder(String orderId, String userId);

    /**
     * 跑腿大厅（market-feed-uv 票 04）：无类型筛选且只看 PUBLISHED 时走 errand_feed_index 索引序扫描 +
     * keyset 游标分页，其余口径走旧直查（索引表无 type 列），索引异常 warn 降级旧直查。
     * 返回页内订单 + 续页游标 + 是否索引路径（索引路径 total 由调用方降口径为 -1）
     */
    ErrandFeedPageVO listOrders(String type, String status, String sort, String cursor, int page, int pageSize);

    long countOrders(String type, String status);

    /**
     * feed 索引全量重建（market-feed-uv 票 06，XXL-Job 手动触发）：清空重灌 errand_feed_index 并把
     * errand_view_count 校准成 errand_view_history 行数，一个事务（读者不见空窗）。
     * 常态无漂移——同步是同事务直写，重建只在出现漂移信号（浏览旁路命中 0 行的 warn、窄表被绕过直改）时手动执行
     */
    void rebuildFeedIndex();

    /**
     * 当前用户在给定订单集合里浏览过的订单 id（大厅已读灰显，market-feed-uv 票 05）：
     * 一页一次批量回查，未登录或集合为空返回空集
     */
    List<String> listViewedOrderIds(String userId, List<String> orderIds);

    /** 订单详情（待审核订单仅作者本人可见，审核员走 moderation 审核队列详情） */
    ErrandOrderEntity orderDetail(String orderId, String currentUserId);

    /** 抢单：条件 UPDATE 先到先得（状态 + 无人持有 + 时限三重校验，并发下仅一人成功）→ LOCKED 并 lock_seq+1 */
    void grabOrder(String orderId, String userId);

    /** 确认接单（锁单→已确认，仅持单人）：抢到手后核对信息再点；不改 lock_seq */
    void acceptLockedOrder(String orderId, String userId);

    /** 放弃（锁单→已发布，仅持单人）：清 taker_id；不扣信用分、不动 taker_canceled、不改 lock_seq */
    void abandonLockedOrder(String orderId, String userId);

    /** 取货：限接单方 */
    void pickupOrder(String orderId, String userId);

    /** 送达：限接单方（24h 未确认自动完成由 XXL-Job 扫描触发） */
    void deliverOrder(String orderId, String userId);

    /** 确认完成（发布方，验收制，无事务）：confirmById，返回解冻总额 + 入账需求 */
    ErrandConfirmResult confirmOrder(String orderId, String userId);

    /** 取消（无事务）：发布方（取货前，终态）/ 接单方（仅已确认，回已发布），返回退款/扣分需求 */
    ErrandCancelResult cancelOrder(String orderId, String userId);

    /** 发起仲裁（CONFIRMED/PICKED_UP/DELIVERED，任一方） */
    void disputeOrder(String orderId, String userId);

    /** 仲裁结案（无事务）：result=cancel 全额退发布方 / complete 划转接单方（同确认完成口径），返回退款/入账需求；事件落 ADMIN 裁决 */
    ErrandArbitrateResult arbitrateOrder(String orderId, String operatorId, String result);

    List<ErrandOrderEntity> listMyOrders(String role, String status, String userId, int page, int pageSize);

    long countMyOrders(String role, String status, String userId);

    /** 某用户发布的进行中跑腿单（作者主页「跑腿」tab，访客口径）：PUBLISHED/LOCKED/CONFIRMED，只列发布不含接单 */
    List<ErrandOrderEntity> listUserPublishedOrders(String userId, int page, int pageSize);

    long countUserPublishedOrders(String userId);

    /** 待支付 15 分钟未支付：超时中心回调或扫描触发 → CANCELLED（无退款） */
    void handlePayTimeout(String orderId);

    /**
     * 锁单 5 分钟未确认：超时中心回调触发 → 释放回 PUBLISHED（清 {@code taker_id}）。
     * {@code lockSeq} 是轮次守卫，陈旧轮次的回调命中 0 行 = 幂等成功；
     * 返回本轮被释放的接单方 id（清空前读出，供发结果通知），未释放返回 null
     */
    ErrandLockTimeoutResult handleLockTimeout(String orderId, int lockSeq);

    /** 待支付超时订单 id 列表（cutoff = now − 15min，XXL-Job 兜底扫描用） */
    List<String> listPayTimeoutOrderIds();

    /** 订单超时 id 列表（PUBLISHED/LOCKED/CONFIRMED/PICKED_UP 且 deadline 已过，每批 100，XXL-Job 兜底扫描用） */
    List<String> listTimeoutOrderIds();

    /** 待审超时订单 id 列表（PENDING_REVIEW 且支付转待审后超 24h 未裁决，每批 100，超时提醒扫描用） */
    List<String> listPendingReviewTimeoutOrderIds();

    /** 送达超时订单 id 列表（DELIVERED 且送达后超 24h 未确认，每批 100，自动完成扫描用） */
    List<String> listAutoCompleteOrderIds();

    /** 内部加载订单（通知编排等 case 层用，无可见性限制；不存在返回 null） */
    ErrandOrderEntity loadOrder(String orderId);

    /** 订单超时（无事务）：CANCELLED，返回退款/扣分需求；幂等忽略返回 null */
    ErrandTimeoutResult handleOrderTimeout(String orderId);

    /** 送达 24h 未确认（无事务）：自动 COMPLETED，返回解冻总额 + 入账需求（与确认同一口径）；幂等忽略返回 null */
    ErrandAutoCompleteResult handleAutoComplete(String orderId);
}
