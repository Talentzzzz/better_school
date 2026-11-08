package com.betterschool.infrastructure.adapter.merge;

import com.betterschool.domain.community.model.valobj.LikeStateVO;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 点赞类写入的<b>唯一入口</b>：提交给合并器 → 等窗口 → 一组一事务落库 → 返回。
 *
 * <p>接口返回成功时，计数<b>已经精确</b>（与关系行条数相等）：事务在返回前已提交，不需要等待，
 * 也不需要事后重算（验收判据 C1）。
 *
 * <h2>降级全在这层，引擎里没有</h2>
 * 引擎只说 {@code ACCEPTED / BUFFER_FULL / CLOSED} 与五个终态（{@link MergeState}），本类负责把它们
 * 翻译成用户能看到的东西（spec §2.6）：
 * <table border="1">
 *   <tr><th>合并器终态</th><th>动作</th><th>用户看到</th></tr>
 *   <tr><td>{@code COMPLETED}</td><td>直接回业务结果</td><td>成功 / 重复赞 / 未赞过</td></tr>
 *   <tr><td>{@code BUFFER_FULL}</td><td>调用方线程用<b>同一个落库方法</b>落组大小 1</td><td>同上</td></tr>
 *   <tr><td>{@code CLOSED}（停机中）</td><td>同上</td><td>同上</td></tr>
 *   <tr><td>{@code TIMEOUT}</td><td>不重试、不落库，回可重试</td><td>「请稍后重试」</td></tr>
 *   <tr><td>{@code FAILED}</td><td>同 {@code TIMEOUT}</td><td>「请稍后重试」</td></tr>
 * </table>
 *
 * <p><b>降级必须复用同一个落库方法</b>（{@link ICountFlushHandler}，也就是合并器自己用的那个）：
 * 另写一套「同步版」SQL，两套迟早漂移，而漂移只在压力最大时暴露——那正是降级生效的时刻。
 *
 * <p><b>{@code REJECTED} 永远不返回给用户</b>：合并器的「没接住」被拆成 {@code BUFFER_FULL} /
 * {@code CLOSED} 两种如实上报，由本类兜成同步写；只有「等超时 / 落库失败」才对外表达为可重试。
 *
 * <p>{@code TIMEOUT} 不回滚也回滚不了：件仍在缓冲区里、大概率还会落库。所以对外只说「请稍后重试」，
 * 客户端重试的重复赞由唯一键吸收（→ {@code DUPLICATE}），不会重复计。
 */
@Slf4j
@Component
public class CountMergeWriter {

    private final CountMerger countMerger;
    private final ICountFlushHandler flushHandler;

    /**
     * {@code flushHandler} 上的 {@code @Lazy} 与 {@link CountMerger} 构造参数上是同一个原因：
     * 落库方法就在 {@code CommunityRepository} 上，而这个仓储的点赞入口又要注入本类——
     * 出口、调用方是同一个 bean，只能把「拿出口」推迟到降级真正发生的那一刻。
     */
    public CountMergeWriter(CountMerger countMerger, @Lazy ICountFlushHandler flushHandler) {
        this.countMerger = countMerger;
        this.flushHandler = flushHandler;
    }

    /**
     * 写一笔点赞类动作并等结果。
     *
     * @param action 动作（{@code POST_LIKE / POST_UNLIKE / COMMENT_LIKE}）
     * @param rowId  <b>要更新的那一行的业务主键字符串</b>（帖子点赞用 {@code postId}，评论点赞用 {@code commentId}）——
     *               也是分片键，合并器不解析事件来推导它
     * @param userId 操作人
     * @return 业务结果；{@code DUPLICATE} / {@code NOT_FOUND} 由调用方翻成对外的业务错误
     * @throws BusinessException 系统繁忙（等超时 / 落库失败 / 降级也失败）——调用方可安全重试
     */
    public LikeStateVO write(CountAction action, String rowId, String userId) {
        CountEvent event = new CountEvent(action, rowId, userId);
        MergeOutcome<LikeState> outcome = countMerger.submitAndWait(rowId, event);
        return switch (outcome.state()) {
            case COMPLETED -> toDomain(outcome.result(), action);
            case BUFFER_FULL -> degrade(action, rowId, event, "缓冲满");
            case CLOSED -> degrade(action, rowId, event, "停机中");
            case TIMEOUT -> throw retryable(action, rowId, "等窗口超时（结果未知，件大概率仍会落库）");
            case FAILED -> throw retryable(action, rowId, "落库失败（整组已回滚）");
        };
    }

    /**
     * 同步降级：组大小 1 调同一个落库方法。
     *
     * @throws BusinessException 降级也失败时抛可重试异常（此时行一定没动，重试安全）
     */
    private LikeStateVO degrade(CountAction action, String rowId, CountEvent event, String reason) {
        log.debug("合并器未接住，降级为同步落库：action={}, rowId={}, reason={}", action, rowId, reason);
        List<LikeState> resultList;
        try {
            resultList = flushHandler.flushGroup(action, rowId, List.of(event));
        } catch (RuntimeException e) {
            log.error("同步降级落库失败：action={}, rowId={}, reason={}", action, rowId, reason, e);
            throw retryable(action, rowId, "降级落库失败");
        }
        if (resultList == null || resultList.isEmpty() || resultList.get(0) == null) {
            // 落库方法漏回填：宁可让调用方重试，也不给一个「成功但不知道成没成」
            log.error("同步降级落库漏回填：action={}, rowId={}, reason={}", action, rowId, reason);
            throw retryable(action, rowId, "降级落库未回填结果");
        }
        return toDomain(resultList.get(0), action);
    }

    /** 引擎的结果枚举 → 域的结果枚举；两套枚举分开是为了让域不依赖基础设施的合并器包 */
    private static LikeStateVO toDomain(LikeState state, CountAction action) {
        if (state == null) {
            throw new IllegalStateException("落库方法回填了 null 业务结果：" + action);
        }
        return switch (state) {
            case LIKED -> LikeStateVO.LIKED;
            case UNLIKED -> LikeStateVO.UNLIKED;
            case DUPLICATE -> LikeStateVO.DUPLICATE;
            case NOT_FOUND -> LikeStateVO.NOT_FOUND;
            // APPLIED 是浏览件的占位值（没有人在等结果）；它出现在点赞路径 = 落库方法用错了动作分支
            case APPLIED -> throw new IllegalStateException(
                    "浏览占位结果不该出现在点赞路径：" + action);
        };
    }

    /**
     * 可重试：行一定没动（或重试会被唯一键吸收），对外统一是「系统繁忙，请稍后重试」。
     * 具体是哪种失败只进日志与 {@code MergeMetrics}，不给用户看内部状态。
     */
    private static BusinessException retryable(CountAction action, String rowId, String detail) {
        log.warn("点赞类写入需重试：action={}, rowId={}, 原因={}", action, rowId, detail);
        return new BusinessException(ResponseCode.SYSTEM_ERROR, ResponseCode.SYSTEM_ERROR.getInfo());
    }
}
