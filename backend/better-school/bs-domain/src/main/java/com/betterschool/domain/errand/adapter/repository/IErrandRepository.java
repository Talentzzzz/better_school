package com.betterschool.domain.errand.adapter.repository;

import com.betterschool.domain.errand.model.entity.ErrandOrderEntity;
import com.betterschool.domain.errand.model.valobj.ErrandFeedCursorVO;
import com.betterschool.domain.errand.model.valobj.ErrandFeedIndexItemVO;
import com.betterschool.domain.errand.model.valobj.ErrandOrderEventVO;
import com.betterschool.domain.errand.model.valobj.ErrandStatusVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 跑腿订单仓储：状态流转（支付/抢单/取货/送达/确认/取消/仲裁/超时）全部走条件 UPDATE（带状态校验），并发安全
 */
public interface IErrandRepository {

    void save(ErrandOrderEntity orderEntity);

    ErrandOrderEntity findById(String orderId);

    /** 公开列表分页（旧直查 / 筛选 / 降级）：type/status 可选过滤，sort 五档与索引路径语义同构（时间键取主表 create_time） */
    List<ErrandOrderEntity> pageList(String type, String status, String sort, int offset, int limit);

    long count(String type, String status);

    /**
     * 跑腿大厅索引序扫描（market-feed-uv 票 04）：errand_feed_index 只取 PUBLISHED（行常驻 + state 快照），
     * 按 (排序键, errand_order_id) 索引序出 id（hot = view_count、time / time_asc = feed_time、
     * price_* = 悬赏），时间升序是同一索引（idx_state_feed）的正向扫描、降序反向；cursor 非 null 时按
     * {@code WHERE (排序键, errand_order_id) < / > (?, ?)} 续页。排序键与热度快照随行带出；
     * 查询异常向上抛，由调用方降级直查
     */
    List<ErrandFeedIndexItemVO> scanFeedIndex(String sortType, ErrandFeedCursorVO cursor, int limit);

    /**
     * feed 索引全量重建（market-feed-uv 票 06）：清空重灌 errand_feed_index（数据源 = errand_order +
     * errand_view_count）并把 errand_view_count 校准成 errand_view_history 行数，
     * 四条语句一个事务——读者要么看到重建前、要么看到重建后的完整索引，不见空窗。
     * 兜底场景 = 13 态快照 / 浏览旁路的同事务直写留下漂移（索引行缺失、计数窄表被绕过直改）
     */
    void rebuildFeedIndex();

    /** 支付确认：PENDING_PAYMENT → PUBLISHED（冻结悬赏、进入大厅）；deadline 由用户指定随单落库，支付只翻状态（过期单拒付）；同事务落 PAID 事件 */
    boolean payById(String orderId, String actorId);

    /** 抢单：PUBLISHED → LOCKED（状态 + 无人持有 + 时限三重校验，先到先得）；同事务 lock_seq+1 与 LOCKED 事件 */
    boolean grab(String orderId, String takerId);

    /**
     * 锁单超时释放（轮次守卫）：LOCKED → PUBLISHED 并清 {@code taker_id}；
     * {@code lock_seq=?} 挡住陈旧轮次回调（单靠状态守卫挡不住）；同事务落 LOCK_TIMEOUT 事件（SYSTEM）。
     * 命中 0 行（已确认 / 已放弃并重抢到新轮次 / 已取消）= 幂等成功、零副作用。
     */
    boolean releaseLockedByTimeout(String orderId, int lockSeq);

    /** 确认接单：LOCKED → CONFIRMED（仅持单人）；不改 lock_seq */
    boolean acceptLockedById(String orderId, String takerId);

    /** 放弃：LOCKED → PUBLISHED（仅持单人，清 taker_id；不落 TAKER_CANCELLED 事件、不改 lock_seq）；同事务 LOCK_ABANDONED 事件 */
    boolean abandonLockedById(String orderId, String takerId);

    /** 当前锁单轮次（防占单计数 member {@code {orderId}:{lockSeq}} 取用；锁单期不变） */
    int lockSeqOf(String orderId);

    /** 最新一条状态流转事件（同订单 event_seq 最大；取消重试幂等判定事实源，无事件返回 null） */
    ErrandOrderEventVO latestEventOf(String orderId);

    /** 该订单上是否已存在「本人 + TAKER_CANCELLED + 同请求编号」事件（请求级幂等判定：存在 = 同一次点击的重放） */
    boolean hasTakerCancelEvent(String orderId, String actorId, String requestId);

    /** 窗口内主动放弃次数（防占单 Redis 降级回落事实源：actor_id + LOCK_ABANDONED + create_time >= since） */
    int countRecentAbandons(String userId, LocalDateTime since);

    /** 窗口内最近一次主动放弃时刻（防占单 Redis 降级回落估算冷却到期；无则 null） */
    LocalDateTime latestAbandonTime(String userId, LocalDateTime since);

    /** 取货：CONFIRMED → PICKED_UP（接单方本人） */
    boolean pickupById(String orderId, String takerId);

    /** 送达：PICKED_UP → DELIVERED（接单方本人） */
    boolean deliverById(String orderId, String takerId);

    /** 发布方确认完成：DELIVERED → COMPLETED */
    boolean confirmById(String orderId, String publisherId);

    /** 发布方取消（限取货前，含锁单期）：PENDING_PAYMENT/PUBLISHED/LOCKED/CONFIRMED → CANCELLED */
    boolean cancelByPublisher(String orderId, String publisherId);

    /** 接单方取消（仅 CONFIRMED）：回 PUBLISHED 并清空接单方；requestId 随 TAKER_CANCELLED 事件落表（请求级幂等判定），可空 */
    boolean cancelByTaker(String orderId, String takerId, String requestId);

    /** 发起仲裁：CONFIRMED/PICKED_UP/DELIVERED → DISPUTED；同事务落 DISPUTED 事件 */
    boolean disputeById(String orderId, String actorId, String actorRole);

    /** 仲裁结案-退发布方：DISPUTED → CANCELLED；同事务落 ARBITRATED 事件 */
    boolean arbitrateCancelById(String orderId, String operatorId);

    /** 仲裁结案-判接单方：DISPUTED → COMPLETED；同事务落 ARBITRATED 事件 */
    boolean arbitrateCompleteById(String orderId, String operatorId);

    /** 超时取消：PUBLISHED/LOCKED/CONFIRMED/PICKED_UP 且 deadline 已到 → CANCELLED */
    boolean markTimeout(String orderId);

    /** 待支付超时取消：PENDING_PAYMENT → CANCELLED（未冻结无退款） */
    boolean cancelPendingPayment(String orderId);

    /** 送达后自动完成：DELIVERED → COMPLETED（发布方 24h 未确认，防白嫖） */
    boolean autoCompleteById(String orderId);

    List<ErrandOrderEntity> listMyPublished(String userId, String status, int offset, int limit);

    long countMyPublished(String userId, String status);

    /** 某用户发布的进行中跑腿单分页（作者主页「跑腿」tab，访客口径）：PUBLISHED/LOCKED/CONFIRMED */
    List<ErrandOrderEntity> listPublicByPublisher(String userId, int offset, int limit);

    long countPublicByPublisher(String userId);

    List<ErrandOrderEntity> listMyTaken(String userId, String status, int offset, int limit);

    long countMyTaken(String userId, String status);


    /** 创建/提交订单后登记"15 分钟未支付"超时任务（业务事务内直插 timeout_task 分表） */
    void schedulePayTimeout(String orderId, long delaySeconds);

    /**
     * 抢单后登记"5 分钟锁单超时"任务（业务事务内直插 timeout_task 分表）。
     * bizId 是复合键 {@code {orderId}:{lockSeq}}——每次锁单一个独立键，中心侧不再撞 {@code uk(type,biz_id)}、
     * 也不会把新锁的到期时刻幂等复位成旧锁的（陈旧任务只影响旧键）。
     */
    void scheduleLockTimeout(String orderId, int lockSeq, long delaySeconds);

    // ---------- 原子用例：仓储方法即一次事务（REQUIRED，与既有事务合流） ----------
    // 0 行口径：条件 UPDATE 未命中且属「状态已被别处改掉 → 本次必须失败」的，方法内抛 BUSINESS_ERROR；
    // 属「重试 / 已被其他路径处理」可重入的，返回 false（或 void 直接返回），由域内按幂等语义收口。

    /** 下单 + 同事务登记"15 分钟未支付"与"deadline 到期取消"两个超时任务 */
    void createWithTimeouts(ErrandOrderEntity orderEntity, int payTimeoutMinutes);

    /**
     * 抢单 + 登记 5 分钟锁单超时任务（同属一个仓储原子用例）。
     * 条件 UPDATE 命中 0 行返回 false（不写注册 outbox）；命中后按**新轮次**写复合
     * {@code bizId={orderId}:{lockSeq}}、{@code action_time=锁单时刻+lockTimeoutMinutes}，
     * 与 PUBLISHED→LOCKED 同事务提交——回滚即无幽灵注册。
     */
    boolean grabWithLockTimeout(String orderId, String takerId, int lockTimeoutMinutes);

    /** 草稿落库（DRAFT，不注册支付超时）+ 创建事件，同属一个仓储原子用例 */
    void saveDraft(ErrandOrderEntity orderEntity);

    /** 草稿全量编辑：条件 UPDATE 仅 DRAFT 行生效（作者与状态位不在 SET 列内，不产生状态迁移） */
    void updateDraft(ErrandOrderEntity orderEntity);

    /**
     * 提交草稿：DRAFT → PENDING_PAYMENT（条件 UPDATE，带作者守卫，0 行抛 BUSINESS_ERROR）
     * + SUBMITTED 事件 + 同事务注册"15 分钟未支付"与"deadline 到期取消"两个超时
     */
    void submitDraftWithTimeouts(String orderId, String publisherId, int payTimeoutMinutes);

    /** 软删草稿：DRAFT → DELETED（条件 UPDATE，带作者守卫）+ DELETED 事件同事务；返回是否本次生效 */
    boolean softDeleteDraft(String orderId, String publisherId);

    /** 支付转 PUBLISHED（冻结悬赏、进入大厅）+ feed 索引行诞生 + 落 bs-errand-paid 事件（条件 UPDATE 0 行抛 BUSINESS_ERROR） */
    void payWithPaidMessage(String orderId, String userId);

    /** 送达（条件 UPDATE 0 行抛 BUSINESS_ERROR）+ 同事务登记"送达 24h 未确认自动完成"超时任务 */
    void deliverWithAutoComplete(String orderId, String takerId, int autoCompleteHours);

    /** 发布方确认完成 + 搜索同步（条件 UPDATE 0 行抛 BUSINESS_ERROR） */
    void confirmWithSearchSync(String orderId, String publisherId);

    /** 发布方取消 + 搜索同步（条件 UPDATE 0 行抛 BUSINESS_ERROR） */
    void cancelByPublisherWithSearchSync(String orderId, String publisherId);

    /** 发起仲裁 + 搜索同步（条件 UPDATE 0 行抛 BUSINESS_ERROR） */
    void disputeWithSearchSync(String orderId, String actorId, String actorRole);

    /** 仲裁结案（退发布方 / 判接单方）+ 搜索同步；返回是否本次生效（0 行 = 状态已变化，不抛） */
    boolean arbitrateWithSearchSync(String orderId, String operatorId, boolean cancel);

    /** 超时取消 + 搜索同步；返回是否本次生效（0 行 = 已被其他路径处理，不抛） */
    boolean markTimeoutWithSearchSync(String orderId);

    /** 送达后自动完成 + 搜索同步；返回是否本次生效（0 行 = 已确认/已仲裁，不抛） */
    boolean autoCompleteWithSearchSync(String orderId);

    /** 按 id 列表查询（不分状态，搜索同步/搜索命中反查用；返回顺序与入参无关，调用方按 id 重排） */
    List<ErrandOrderEntity> listByIds(List<String> orderIdList);

    /**
     * 搜索索引全量重建的游标页（uuid-biz-id 票 05）：业务 ID 是 UUID 字符串，按 errand_order_id
     * 字典序推进（初值空串），不再按物理 id（无界结果集一律游标化）
     */
    List<String> listIdsAfter(String lastId, int limit);

    /** 落一条"搜索索引同步"待发送消息（必须与订单状态变更同事务调用，实现层写 outbox 表） */
    void saveSearchSyncMessage(String orderId);

    /** 落一条"支付成功进入待审"待发送消息（topic bs-errand-paid，必须与支付状态变更同事务调用） */
    void saveErrandPaidMessage(String orderId);

    /**
     * 落一条"订单状态变化"WS 推送事件（topic bs-order-push，实现层写 outbox 表）：状态迁移成功后由
     * 域服务调用，消费后推 ORDER 帧给订单双方在线者（详情页实时刷新）。推送是加速路径——独立小事务
     * （迁移方法本身恒已提交），极端时序漏推无害：详情页下次拉取/通知中心兜底
     */
    void saveOrderPushEvent(String orderId, ErrandStatusVO status);

    /**
     * 跑腿订单浏览打点（UV 口径，market-feed-uv 票 02）：fire-and-forget，入队即返回，窗口过后一组一个
     * 事务——{@code errand_view_history} 明细 INSERT IGNORE + 全组刷 viewed_at + {@code errand_view_count}
     * 加速列 += 新增数（与商品浏览同构）。
     *
     * <p><b>匿名（userId 为 null）不计入</b>：明细主键的一半是 user_id，无主无处可落——按
     * 「INSERT 成功才算一次浏览」的口径直接跳过。落库<b>允许丢</b>：失败只计数、不告警、不回补，
     * 也<b>不影响请求结果</b>。
     */
    void recordOrderView(String orderId, String userId);

    /**
     * 订单当前的浏览 UV 数（{@code errand_view_count} 加速列；真源 {@code errand_view_history} 行数）。
     * 计数行不存在（从未被登录用户浏览过）返回 0。详情读路径接线用（跑腿此前无浏览量）。
     */
    long viewCountOf(String orderId);

    /**
     * 当前用户在给定订单集合里浏览过的订单 id（大厅列表已读灰显批量回显，market-feed-uv 票 05）：
     * 走 {@code errand_view_history} 主键 {@code (user_id, errand_order_id)} 前缀，一页一次 IN 查询。
     * userId 为 null（未登录）或集合为空返回空集
     */
    List<String> selectViewedOrderIds(String userId, List<String> orderIdList);
}
