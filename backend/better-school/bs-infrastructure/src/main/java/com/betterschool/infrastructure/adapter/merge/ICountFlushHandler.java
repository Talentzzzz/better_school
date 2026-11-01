package com.betterschool.infrastructure.adapter.merge;

import java.util.List;

/**
 * 落库方法：合并器唯一认识的出口。真身分两处——{@code CommunityRepository}（点赞 / 取消赞 /
 * 评论赞 / 帖子浏览）与 {@code TradeRepository#flushViewGroup}（商品浏览），由
 * {@link CountFlushDispatcher} 按动作分发，对引擎呈现为<b>一个</b>实现。
 *
 * <p>一组一个事务。组的边界 = 同一分片内<b>同一动作 × 同一行主键</b>，由合并器二次分组决定：
 * 同一分片里可能混着几个不同的帖子 / 商品（哈希撞片），把它们塞进同一个事务会让锁集变大、
 * 失败时回滚到无关的行，也让「部分撞唯一键回给具体哪几件」失去落点。
 *
 * <h2>三条约定</h2>
 * <ol>
 *   <li><b>返回逐件结果，顺序与入参一致</b>；返回 {@code null}、长度不足或某一位为 {@code null}
 *       都算那几件漏回填，合并器补 {@link MergeState#FAILED}（I1：绝不让等待者悬到超时）。
 *       <b>浏览件没有业务结果，但同样必须回一组等长、非 null 的占位值</b>——否则这些件会被算成
 *       {@code failed}，{@code flushed} 与合并比两个读数一起失真；</li>
 *   <li><b>抛异常 = 整组回滚</b>，组内所有件补 {@code FAILED}，别的组照常跑
 *       （一组一事务 ⇒ 失败不扩散）；</li>
 *   <li>落库方法<b>不得</b>在这里做降级 / 重试 / 拆分：那些是 case 层的事，引擎只数件。</li>
 * </ol>
 *
 * <p>实现必须自己开事务（{@code @Transactional} 或 {@code TransactionTemplate}），合并器不持有事务边界
 * ——它只保证「同一分片同一时刻至多一组在跑」。单条语句的组（浏览）不加事务也是原子的。
 */
@FunctionalInterface
public interface ICountFlushHandler {

    /**
     * @param action   该组统一的动作类型
     * @param shardKey 该组统一的<b>业务行主键字符串</b>（= 调用方给的 shardKey，postId / commentId 等）
     * @param events   该组的事件，顺序 = 入队顺序
     * @return 与 {@code events} 一一对应的逐件业务结果
     */
    List<LikeState> flushGroup(CountAction action, String shardKey, List<CountEvent> events);
}
