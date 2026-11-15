package com.betterschool.marketing.domain.inventory.service.merge;

import com.betterschool.marketing.domain.inventory.model.valobj.InventoryClaimRequestVO;
import com.betterschool.marketing.domain.inventory.model.valobj.ClaimOutcome;
import com.betterschool.marketing.domain.inventory.model.valobj.DeductResult;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.types.inventory.AwardInventoryType;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 缓冲里的一件：分类与数据在同一对象上，队列里只有这一种。
 *
 * <p>「动作决定一切」由 {@link #action} 字段而非类层次表达；两个数据槽
 * {@code deductRequest} / {@code claimRequest} 由静态工厂保证恰一个非空——
 * 落库出口按 {@code action} 分流后拿到的就是精确类型，
 * 不需要 {@code Object payload} 与 {@code @SuppressWarnings} 强转。
 *
 * <p>分组的三个维度（动作 × type × shardKey）与失败值在工厂里定格：不可变、构造即终。
 *
 * <p>{@link #complete} 用 {@link AtomicBoolean} 抢一次性（恰一终态）：落库漏回填、排空超时、
 * 并发补齐三条路径都可能碰同一件，只有第一个赢家能写结果——第二个人拿到 {@code false}，
 * 调用方据此决定要不要补失败态。{@code CompletableFuture.complete} 本身幂等，但计数不能重复算，
 * 所以幂等必须在这里体现。
 *
 * <p>结果类型统一为 {@code Object}（{@code DeductResult} / {@code ClaimOutcome} 是两个独立的枚举，
 * 没有公共父类型）：分流之后的每条落库路径只会回填自己的结果类型，
 * 强转只出现在 {@code QuotaMergeBuffer} 的两个 {@code await} 出口，各一处且必然成立。
 */
final class Pending {

    private final CompletableFuture<Object> future = new CompletableFuture<>();
    private final AtomicBoolean settled = new AtomicBoolean();

    /** 分组键第一维，也是落库分流的判据 */
    private final Action action;
    /** 分片键（= 奖品库存账户 awardId），入队时路由用 */
    private final String shardKey;
    /** 分组键第二维（账户类型）；确认件为 {@code null}（分组键只用于分桶，不参与落库） */
    private final AwardInventoryType type;
    /** 落库入参之一：{@code action == DEDUCT} 时非空（其余情况恒为 null） */
    private final InventoryDeductRequestVO deductRequest;
    /** 落库入参之一：{@code action == CLAIM} 时非空（其余情况恒为 null） */
    private final InventoryClaimRequestVO claimRequest;
    /** 失败态的值（{@code DeductResult.FAILED} / {@code ClaimOutcome.FAILED}）：让回填不必知道结果类型 */
    private final Object failureValue;

    private Pending(Action action, String shardKey, AwardInventoryType type,
                    InventoryDeductRequestVO deductRequest, InventoryClaimRequestVO claimRequest, Object failureValue) {
        this.action = action;
        this.shardKey = shardKey;
        this.type = type;
        this.deductRequest = deductRequest;
        this.claimRequest = claimRequest;
        this.failureValue = failureValue;
    }

    /** 预扣件：分组维度从 request 取，失败值定格为 {@code DeductResult.FAILED} */
    static Pending deduct(InventoryDeductRequestVO request) {
        return new Pending(Action.DEDUCT, request.awardId(), request.type(), request, null, DeductResult.FAILED);
    }

    /** 确认件：分片键同样从 request 取（{@code awardId} 在对象里），type 定格为 {@code null}，失败值定格为 {@code ClaimOutcome.FAILED} */
    static Pending claim(InventoryClaimRequestVO request) {
        return new Pending(Action.CLAIM, request.awardId(), null, null, request, ClaimOutcome.FAILED);
    }

    Action action() {
        return action;
    }

    String shardKey() {
        return shardKey;
    }

    AwardInventoryType type() {
        return type;
    }

    /** 仅 {@code action == DEDUCT} 的件可读（落库分流之后） */
    InventoryDeductRequestVO deductRequest() {
        return deductRequest;
    }

    /** 仅 {@code action == CLAIM} 的件可读（落库分流之后） */
    InventoryClaimRequestVO claimRequest() {
        return claimRequest;
    }

    /**
     * 回填业务结果。
     *
     * @return true = 本次是第一个终态；false = 已经有人回填过
     */
    boolean complete(Object result) {
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

    /** 是否已有终态（入队失败的「已回填」判定用） */
    boolean isSettled() {
        return settled.get();
    }

    /**
     * 阻塞等结果（只有 {@code submitAndWait} 会调）。
     * {@link ExecutionException} 不该出现（引擎只用 {@code complete} 回填），真出现也按失败态兜住。
     */
    Object await(long timeoutMs) throws TimeoutException, InterruptedException {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            return failureValue;
        }
    }
}
