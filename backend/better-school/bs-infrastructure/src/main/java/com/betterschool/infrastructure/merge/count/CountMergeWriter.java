package com.betterschool.infrastructure.merge.count;

import com.betterschool.domain.community.model.valobj.LikeStateVO;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 点赞类写入的<b>唯一入口</b>：提交给合并器 → 等窗口 → 一组一事务落库 → 返回。
 *
 * <p>接口返回成功时，计数<b>已经精确</b>（与关系行条数相等）：事务在返回前已提交，不需要等待，
 * 也不需要事后重算（验收判据 C1）。
 *
 * <h2>降级在引擎里，本类只做翻译</h2>
 * {@link CountMerger#commit} 只回三态（{@link MergeState}），本类负责把它们翻成用户能看到的东西
 * （spec §2.6）：
 * <table border="1">
 *   <tr><th>合并器终态</th><th>用户看到</th></tr>
 *   <tr><td>{@code COMPLETED}</td><td>成功 / 重复赞 / 未赞过</td></tr>
 *   <tr><td>{@code TIMEOUT}</td><td>「请稍后重试」</td></tr>
 *   <tr><td>{@code FAILED}</td><td>同 {@code TIMEOUT}</td></tr>
 * </table>
 *
 * <p><b>缓冲满 / 停机中的同步降级已经在 {@code commit} 里做完</b>（复用同一个落库方法），所以这里
 * 没有降级分支：降级逻辑只写一份，将来再加一个「等结果」的用途不必抄第二遍。
 * {@code BUFFER_FULL} / {@code CLOSED} 两个 case 只是枚举穷尽所需，真冒到这儿就是引擎被改坏了。
 *
 * <p>{@code TIMEOUT} 不回滚也回滚不了：件仍在缓冲区里、大概率还会落库。所以对外只说「请稍后重试」，
 * 客户端重试的重复赞由唯一键吸收（→ {@code DUPLICATE}），不会重复计。
 */
@Slf4j
@Component
public class CountMergeWriter {

    private final CountMerger countMerger;

    public CountMergeWriter(CountMerger countMerger) {
        this.countMerger = countMerger;
    }

    /**
     * 写一笔点赞类动作并等结果。
     *
     * @param action 动作（{@code POST_LIKE / POST_UNLIKE / COMMENT_LIKE}）
     * @param rowId  <b>要更新的那一行的业务主键字符串</b>（帖子点赞用 {@code postId}，评论点赞用 {@code commentId}）——
     *               也是分片键，合并器不解析事件来推导它
     * @param userId 操作人
     * @return 业务结果；{@code DUPLICATE} / {@code NOT_FOUND} 由调用方翻成对外的业务错误
     * @throws BusinessException 系统繁忙（等超时 / 落库失败，含降级也失败）——调用方可安全重试
     */
    public LikeStateVO write(CountAction action, String rowId, String userId) {
        CountEvent event = new CountEvent(action, rowId, userId);
        MergeResult<LikeState> outcome = countMerger.commit(rowId, event);
        return switch (outcome.state()) {
            case COMPLETED -> toDomain(outcome.result(), action);
            case TIMEOUT -> throw retryable(action, rowId, "等窗口超时（结果未知，件大概率仍会落库）");
            case FAILED -> throw retryable(action, rowId, "落库失败（整组已回滚，或同步降级也失败）");
            // 这两种引擎内部已就地降级；走到这里说明通用层被改坏了
            case BUFFER_FULL, CLOSED -> throw new IllegalStateException(
                    "合并在入队阶段就该被降级，这两态不该冒到业务出口：" + outcome.state());
        };
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
