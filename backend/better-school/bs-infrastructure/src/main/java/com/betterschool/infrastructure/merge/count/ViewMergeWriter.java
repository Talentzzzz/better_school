package com.betterschool.infrastructure.merge.count;

import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 浏览类写入的<b>唯一入口</b>（帖子 / 商品 / 跑腿浏览）：提交给合并器 → 等终态 → 返回
 * （spec：统一等待语义——浏览量与点赞量一样是「要么落了要么没落」的提交语义）。
 * 三个域的浏览都是 UV 口径——明细表（一人一对象一行）+ 计数加速列
 * （{@code user_view_history} / {@code post_view_count}、{@code product_view_history} /
 * {@code product_view_count}、{@code errand_view_history} / {@code errand_view_count}）。
 *
 * <h2>为什么浏览也 commit 等结果（语义翻转）</h2>
 * 旧设计浏览走 fire-and-forget（允许丢、{@code droppedViews} 计数说话）。统一等待语义拍板后，
 * 浏览与点赞共用同一套引擎、同一个 {@code commit} 入口、同一套三态与降级：
 * 等待落库终态，超时 = 结果未知（{@code TIMEOUT} → 上层可重试 → 重放被明细唯一键吸收），
 * 不再有「允许丢」的灰色地带，也不再有 {@code droppedViews} 指标。已知的代价（用户知情拍板，
 * 非缺陷）：浏览请求 RT 增加落库时长；每请求一个终态对象的成本回归。
 *
 * <h2>降级在引擎里，本类只做翻译</h2>
 * {@link CountMerger#commit} 只回三态（{@link MergeState}），本类负责把它们翻成用户能看到的东西
 * ——与 {@link CountMergeWriter} 完全同口径：
 * <table border="1">
 *   <tr><th>合并器终态</th><th>用户看到</th></tr>
 *   <tr><td>{@code COMPLETED}</td><td>浏览已记录（{@code APPLIED} 占位结果）</td></tr>
 *   <tr><td>{@code TIMEOUT}</td><td>「请稍后重试」（结果未知，件大概率仍会落库；重放被唯一键吸收）</td></tr>
 *   <tr><td>{@code FAILED}</td><td>「请稍后重试」（确定没落）</td></tr>
 * </table>
 *
 * <p><b>缓冲满 / 停机中的同步降级已经在 {@code commit} 里做完</b>（复用同一个落库方法），所以这里
 * 没有降级分支。{@code BUFFER_FULL} / {@code CLOSED} 两个 case 只是枚举穷尽所需，真冒到这儿就是
 * 引擎被改坏了。
 *
 * <p><b>幂等口径不变</b>：浏览沿用明细唯一键 + INSERT IGNORE 影响行数——统一语义不引入新的
 * 去重机制，超时重放的重复浏览由唯一键吸收（重复浏览只刷 viewed_at 不加数）。
 */
@Slf4j
@Component
public class ViewMergeWriter {

    private final CountMerger countMerger;

    public ViewMergeWriter(CountMerger countMerger) {
        this.countMerger = countMerger;
    }

    /**
     * 记录一次浏览并等待落库终态。落库失败 / 等待超时抛可重试异常（与点赞同一口径）：
     * 浏览的对外表现不再是「没记上也不吭声」，而是「系统繁忙，请稍后重试」——客户端重放由
     * 明细唯一键吸收，不会重复计。
     *
     * @param action {@code POST_VIEW} / {@code PRODUCT_VIEW} / {@code ERRAND_VIEW}
     * @param rowId  <b>要更新的那一行的业务主键字符串</b>（postId / productId / errandOrderId）——也是
     *               合并键，合并器不解析事件
     * @param userId 浏览者，<b>三个域都必须带</b>：UV 明细表主键的一半是 user_id，
     *               INSERT IGNORE 靠它裁决重复；哨兵 0 会把全体匿名塌成一行——那不是 UV。
     *               匿名访问（无主）在仓储层拦下、直接不计入
     * @throws BusinessException 系统繁忙（等超时 / 落库失败，含降级也失败）——调用方可安全重试
     */
    public void write(CountAction action, String rowId, String userId) {
        if (action != CountAction.POST_VIEW && action != CountAction.PRODUCT_VIEW
                && action != CountAction.ERRAND_VIEW) {
            // 点赞类也走 commit，但从点赞入口走——从这里溜进去会把点赞当浏览合并（动作不混组）
            throw new IllegalArgumentException("ViewMergeWriter 只处理浏览动作，收到：" + action);
        }
        if (userId == null || userId.isBlank()) {
            // UV 明细行没有 user_id 就无处可落，哨兵空值会把全体匿名塌成一行——那不是 UV，直接拦
            throw new IllegalArgumentException("浏览必须带浏览者 userId，收到空（匿名访问应在仓储层拦下不计入）");
        }
        CountEvent event = new CountEvent(action, rowId, userId);
        MergeResult<LikeState> outcome = countMerger.commit(rowId, event);
        switch (outcome.state()) {
            case COMPLETED -> {
                // 浏览件没有业务结果，APPLIED 只是占位——落了就是落了
            }
            case TIMEOUT -> throw retryable(action, rowId, "等待落库超时（结果未知，件大概率仍会落库）");
            case FAILED -> throw retryable(action, rowId, "落库失败（整组已回滚，或同步降级也失败）");
            // 这两种引擎内部已就地降级；走到这里说明通用层被改坏了
            case BUFFER_FULL, CLOSED -> throw new IllegalStateException(
                    "合并在入队阶段就该被降级，这两态不该冒到业务出口：" + outcome.state());
        }
    }

    /**
     * 可重试：行一定没动（或重试会被明细唯一键吸收），对外统一是「系统繁忙，请稍后重试」。
     * 具体是哪种失败只进日志与 {@code MergeMetrics}，不给用户看内部状态。
     */
    private static BusinessException retryable(CountAction action, String rowId, String detail) {
        log.warn("浏览写入需重试：action={}, rowId={}, 原因={}", action, rowId, detail);
        return new BusinessException(ResponseCode.SYSTEM_ERROR, ResponseCode.SYSTEM_ERROR.getInfo());
    }
}
