package com.betterschool.infrastructure.adapter.merge;

import com.betterschool.infrastructure.adapter.repository.community.CommunityRepository;
import com.betterschool.infrastructure.adapter.repository.errand.ErrandRepository;
import com.betterschool.infrastructure.adapter.repository.trade.TradeRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 合并器的<b>唯一出口</b>：按动作把一组件交给「拥有那张表」的域仓储。
 *
 * <h2>为什么需要这一层</h2>
 * 点赞 / 取消赞 / 评论赞 / 帖子浏览打的是 {@code post}、{@code post_like}、{@code post_like_count}、
 * {@code post_comment}，落库方法在 {@link CommunityRepository}；商品浏览打的是
 * {@code product_view_history} / {@code product_view_count}，落库方法在 {@link TradeRepository}；
 * 跑腿浏览打的是 {@code errand_view_history} / {@code errand_view_count}，落库方法在
 * {@link ErrandRepository}。而 spec §2.2 定的是<b>一个合并器实例</b>（点赞与浏览共用一份
 * 分片与线程池——它们打的是同一批热点行）。一个引擎、一个出口接口、三个域的落库方法，中间就必须有人
 * 按动作分发，否则 {@link CountMerger} 要么认识三个仓储（引擎耦合业务表），要么被注入多个
 * {@code ICountFlushHandler}（注入点歧义）。
 *
 * <p>分发是<b>按动作</b>而不是按 key：key 只说明「打哪一行」，动作才说明「打哪张表」。
 *
 * <p><b>为什么 {@code @Primary}</b>：能让引擎和降级路径都只认一个 {@code ICountFlushHandler} 类型的
 * bean，不必逐个改注入点（{@link CountMerger}、{@link CountMergeWriter}、{@link ViewMergeWriter}）。
 *
 * <p><b>为什么三个依赖都 {@code @Lazy}</b>：本类是出口，三个仓储的写入入口又都要注入
 * {@link CountMergeWriter} / {@link ViewMergeWriter} —— 出口与调用方互相指着，只有把「拿仓储」
 * 推迟到 flush / 降级那一刻才能解开。
 */
@Primary
@Component
public class CountFlushDispatcher implements ICountFlushHandler {

    private final CommunityRepository communityRepository;
    private final TradeRepository tradeRepository;
    private final ErrandRepository errandRepository;

    public CountFlushDispatcher(@Lazy CommunityRepository communityRepository,
                                @Lazy TradeRepository tradeRepository,
                                @Lazy ErrandRepository errandRepository) {
        this.communityRepository = communityRepository;
        this.tradeRepository = tradeRepository;
        this.errandRepository = errandRepository;
    }

    /**
     * 按动作分发。{@code switch} 覆盖全部动作且不写 {@code default}，于是新增一个动作时编译器会在
     * 这里逼一次「它该落到哪个域的落库方法」的决策，而不是让它在运行期掉进某个静默分支。
     */
    @Override
    public List<LikeState> flushGroup(CountAction action, String shardKey, List<CountEvent> events) {
        return switch (action) {
            case POST_LIKE, POST_UNLIKE, COMMENT_LIKE, POST_VIEW ->
                    communityRepository.flushGroup(action, shardKey, events);
            case PRODUCT_VIEW -> tradeRepository.flushViewGroup(shardKey, events);
            case ERRAND_VIEW -> errandRepository.flushViewGroup(shardKey, events);
        };
    }
}
