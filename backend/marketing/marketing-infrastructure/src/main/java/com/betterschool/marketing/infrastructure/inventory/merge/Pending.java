package com.betterschool.marketing.infrastructure.inventory.merge;

import com.betterschool.marketing.domain.inventory.model.valobj.ClaimItem;
import com.betterschool.marketing.domain.inventory.model.valobj.ClaimOutcome;
import com.betterschool.marketing.domain.inventory.model.valobj.DeductResult;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.types.inventory.InventorySegmentVO;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 缓冲里的一件：动作 + 分片键 + 落库入参 + 等待者的 future。
 *
 * <p>与 better-school 计数侧那份 {@code Pending} 同形（字段语义、{@code complete} 的幂等口径一致），
 * 差别只有两处，都来自「库存侧只有一种调用方」：
 * <ol>
 *   <li><b>没有 fire-and-forget 分支</b>：每一个预扣 / 确认都在等结果，所以 future 恒非 null，
 *       也没有 {@code complete()} 的空操作路径；</li>
 *   <li><b>结果类型是泛型 {@code R}</b>：预扣回 {@link DeductResult}、确认回 {@link ClaimOutcome}，
 *       引擎按分组把落库方法返回的逐件结果原样回填，不需要在中间转成第三种枚举。</li>
 * </ol>
 *
 * <h2>恰一个终态（I1 的一半）</h2>
 * {@link #complete} 用 {@link AtomicBoolean} 抢一次性：落库方法漏回填、排空超时、并发补齐
 * 三条路径都可能碰同一件，只有第一个赢家能写结果——第二个人返回 {@code false}，调用方据此决定要不要
 * 计 {@code failed}。{@code CompletableFuture.complete} 本身也是幂等的，但计数不能重复算，所以幂等
 * 必须在本类里体现，而不是依赖 future 的返回值。
 *
 * @param <R> 这一件的业务结果类型：{@link DeductResult} 或 {@link ClaimOutcome}
 */
final class Pending<R> {

    private final QuotaAction action;
    private final long shardKey;
    /** 预扣的段（分组键的第二维）；确认没有段，恒 null */
    private final InventorySegmentVO segment;
    /** 落库入参：{@link QuotaAction#DEDUCT} ⇒ {@link InventoryDeductRequestVO}，{@link QuotaAction#CLAIM} ⇒ {@link ClaimItem} */
    private final Object payload;
    /** 失败态的值（{@code DeductResult.FAILED} / {@code ClaimOutcome.FAILED}）：让回填不必知道 R 是什么 */
    private final R failureValue;
    private final CompletableFuture<R> future = new CompletableFuture<>();
    private final AtomicBoolean settled = new AtomicBoolean();

    private Pending(QuotaAction action,
                    long shardKey,
                    InventorySegmentVO segment,
                    Object payload,
                    R failureValue) {
        this.action = action;
        this.shardKey = shardKey;
        this.segment = segment;
        this.payload = payload;
        this.failureValue = failureValue;
    }

    /** 一件预扣 */
    static Pending<DeductResult> deduct(long invId, InventoryDeductRequestVO request) {
        return new Pending<>(QuotaAction.DEDUCT, invId, request.segment(), request, DeductResult.FAILED);
    }

    /** 一件兑换确认（只有实物段，没有 segment） */
    static Pending<ClaimOutcome> claim(long invId, ClaimItem item) {
        return new Pending<>(QuotaAction.CLAIM, invId, null, item, ClaimOutcome.FAILED);
    }

    QuotaAction action() {
        return action;
    }

    long shardKey() {
        return shardKey;
    }

    InventorySegmentVO segment() {
        return segment;
    }

    /**
     * 落库入参（静态类型由 {@link #action()} 决定，由落库方法在自己的分支里转）。
     *
     * <p>合并器<b>不解析</b>它来推导分片键（spec §2.8 前提①）；这里只在分组时读它的 {@code segment}，
     * 因为「动作 × segment」就是落库方法的入参形状（spec §4.1）。
     */
    Object payload() {
        return payload;
    }

    /**
     * 回填业务结果。
     *
     * @return true = 本次是第一个终态（调用方可以计数）；false = 已经有人回填过
     */
    boolean complete(R result) {
        if (!settled.compareAndSet(false, true)) {
            return false;
        }
        future.complete(result);
        return true;
    }

    /**
     * 用失败态终结（落库漏回填 / 整组回滚 / 停机排空）。
     *
     * @return true = 本次是第一个终态
     */
    boolean fail() {
        return complete(failureValue);
    }

    /** 是否已有终态（落库后的「漏回填」判定用） */
    boolean isSettled() {
        return settled.get();
    }

    /**
     * 阻塞等结果（只有 {@code submitAndWait} 会调）。
     *
     * <p>{@link ExecutionException} 不该出现（引擎只用 {@code complete} 回填，从不用
     * {@code completeExceptionally}），真出现也按失败态兜住——不把技术细节泄给调用方。
     */
    R await(long timeoutMs) throws TimeoutException, InterruptedException {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            return failureValue;
        }
    }
}
