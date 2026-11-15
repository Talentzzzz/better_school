package com.betterschool.infrastructure.adapter.merge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 浏览类写入的<b>唯一入口</b>（帖子 / 商品 / 跑腿浏览）：入队即返回，窗口过后一组一个事务落库
 * （spec §3.2）。三个域的浏览都是 UV 口径——明细表（一人一对象一行）+ 计数加速列
 * （{@code user_view_history} / {@code post_view_count}、{@code product_view_history} /
 * {@code product_view_count}、{@code errand_view_history} / {@code errand_view_count}）。
 *
 * <h2>为什么浏览走 submitAsync 而不是 submitAndWait</h2>
 * 浏览没有关系行、没有真相表可对账——<b>它自己就是唯一记录</b>，所以语义是「异步 +1，允许丢」
 * （spec §1.2）。既然允许丢，就没有任何理由让请求线程等那 5ms 窗口：{@code submitAsync} 连
 * future 都不分配，10k/s 的浏览不会每秒造一万个 {@code CompletableFuture} + park/unpark。
 * 这条链路的 HTTP 时延因此与 {@code maxFlushDelayMillis} 无关。
 *
 * <h2>降级：说「没接住」的那两句话，各对应一次独立落库（组大小 1）</h2>
 * 合并器的异步入口只回 {@code ACCEPTED / BUFFER_FULL / CLOSED}（没有 {@code TIMEOUT}——不等的人
 * 不会超时）。后两种由本类兜成<b>调用方线程上的组大小 1 落库</b>（帖子浏览 = 完整的 UV 三步事务）；那一次也失败，
 * 就丢弃并计入 {@link #droppedViews()}——<b>不告警、不回补、不影响请求结果</b>（spec §2.6、
 * §3.4：至多一次）。
 *
 * <p><b>降级必须复用同一个落库方法</b>（{@link ICountFlushHandler}，也就是合并器自己用的那个）：
 * 另写一套「同步版」SQL，两套迟早漂移，而漂移只在压力最大时暴露——那正是降级生效的时刻。
 *
 * <h2>丢弃的可见性分两处，别混</h2>
 * <ul>
 *   <li>{@link #droppedViews()}：<b>本类</b>丢的（缓冲满 / 停机中降级也失败）——case 层的读数；</li>
 *   <li>{@code MergeMetrics.failed} / {@code drainedFailed}：<b>引擎</b>丢的（已入队后落库失败 /
 *       停机排空超时）——引擎的读数。</li>
 * </ul>
 * 两者相加才是「丢了多少次浏览」。之所以不再「失败并回窗口」，是因为重投源头（MQ）已经消失：
 * 点赞的幂等由唯一键承担，浏览本无幂等，回补只会把「丢一次」变成「可能重一次」，反而更不准。
 *
 * <h2>长尾代价（不是待优化项）</h2>
 * 合并的收益来自「同一行的并发」：单热点下合并比随速率涨，<b>长尾下合并比 ≈ 1，合并器在这条路上是
 * 净成本</b>（多一次入队 + 5ms 窗口）。这是选「统一一条路」（点赞与浏览共用一份分片与线程池，
 * spec §2.2）的代价，不是待优化的 bug——真要优化就该为浏览单开一条直写路径，那会把两份参数、
 * 两份观测、两个降级口径都带回来。
 */
@Slf4j
@Component
public class ViewMergeWriter {

    private final CountMerger countMerger;
    private final ICountFlushHandler flushHandler;

    /** case 层丢掉的浏览件数（缓冲满 / 停机中降级也失败）；与引擎的 failed / drainedFailed 分开读 */
    private final AtomicLong droppedViews = new AtomicLong();

    /**
     * {@code flushHandler} 上的 {@code @Lazy} 与 {@link CountMergeWriter} 是同一个原因：落库方法在
     * 两个域仓储上（出口经 {@code CountFlushDispatcher} 分发），而那两个仓储的浏览入口又要注入本类——
     * 出口与调用方是同一组 bean，只能把「拿出口」推迟到降级真正发生的那一刻。
     */
    public ViewMergeWriter(CountMerger countMerger, @Lazy ICountFlushHandler flushHandler) {
        this.countMerger = countMerger;
        this.flushHandler = flushHandler;
    }

    /**
     * 记录一次浏览（fire-and-forget）。<b>绝不抛异常</b>：浏览失败的对外表现是「没记上」，不是 500。
     *
     * @param action {@code POST_VIEW} / {@code PRODUCT_VIEW} / {@code ERRAND_VIEW}
     * @param rowId  <b>要更新的那一行的业务主键字符串</b>（postId / productId / errandOrderId）——也是分片键，
     *               合并器不解析事件
     * @param userId 浏览者，<b>三个域都必须带</b>：UV 明细表主键的一半是 user_id，
     *               INSERT IGNORE 靠它裁决重复；哨兵 0 会把全体匿名塌成一行——那不是 UV。
     *               匿名访问（无主）在仓储层拦下、直接不计入
     */
    public void write(CountAction action, String rowId, String userId) {
        if (action != CountAction.POST_VIEW && action != CountAction.PRODUCT_VIEW
                && action != CountAction.ERRAND_VIEW) {
            // 点赞类必须等结果（submitAndWait），从这里溜进去会被当成「没人等」而静默丢弃结果
            throw new IllegalArgumentException("ViewMergeWriter 只处理浏览动作，收到：" + action);
        }
        if (userId == null || userId.isBlank()) {
            // UV 明细行没有 user_id 就无处可落，哨兵空值会把全体匿名塌成一行——那不是 UV，直接拦
            throw new IllegalArgumentException("浏览必须带浏览者 userId，收到空（匿名访问应在仓储层拦下不计入）");
        }
        CountEvent event = new CountEvent(action, rowId, userId);
        SubmitOutcome outcome = countMerger.submitAsync(rowId, event);
        if (outcome == SubmitOutcome.ACCEPTED) {
            return;
        }
        degrade(action, rowId, event, outcome);
    }

    /**
     * 合并器没接住：调用方线程同步落一次「组大小 1」。
     *
     * <p>成功与失败的差别只有 {@link #droppedViews()} 一个数——用户拿到的都是「浏览过了」。
     */
    private void degrade(CountAction action, String rowId, CountEvent event, SubmitOutcome outcome) {
        String reason = outcome == SubmitOutcome.BUFFER_FULL ? "缓冲满" : "停机中";
        try {
            flushHandler.flushGroup(action, rowId, List.of(event));
            log.debug("浏览未入队，已在调用方线程同步落一组：action={}, rowId={}, reason={}", action, rowId, reason);
        } catch (RuntimeException e) {
            // 丢弃是允许的（spec §1.2）：不告警、不回补，只让读数说话 —— 每次失败刷一条 WARN
            // 会把一个「可接受的丢」放大成日志风暴，压力最大时反而什么都看不见
            droppedViews.incrementAndGet();
            log.debug("浏览降级也失败，按丢弃计：action={}, rowId={}, reason={}, 累计丢弃={}",
                    action, rowId, reason, droppedViews.get(), e);
        }
    }

    /** 本类丢掉的浏览件数（缓冲满 / 停机中且降级也失败）。引擎侧丢弃看 {@code MergeMetrics} */
    public long droppedViews() {
        return droppedViews.get();
    }
}
