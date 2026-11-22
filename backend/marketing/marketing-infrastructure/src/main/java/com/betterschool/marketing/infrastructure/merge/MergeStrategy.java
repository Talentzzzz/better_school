package com.betterschool.marketing.infrastructure.merge;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 一类合并策略的定义：<b>怎么路由、怎么分组、怎么落库</b>。一个策略对应一个 {@link MergeEngine} 实例，
 * 在引擎构造时注入并定格——引擎因此不认识任何业务类型，件里也不必带着策略跑。
 *
 * <p>「一个用途一个策略」由结构保证：不同用途各有自己的引擎、自己的缓冲，
 * 即使分片键撞了也不会进同一事务（打不同的表，合成一个只会放大锁集）。
 * 副产品是批内分组只剩 {@code shardKey} 一维，回填与降级都不再需要泛型强转。
 *
 * @param <T> 件
 * @param <R> 业务结果
 */
public class MergeStrategy<T, R> {

    /**
     * 「一组落一次」的落库函数：引擎唯一需要的业务知识，调用方以 lambda 传入。
     *
     * <p>三条约定：返回逐件结果且顺序与入参一致（{@code null} / 长度不足 / 某位为 {@code null}
     * 都算漏回填，引擎补 {@code FAILED}）；抛异常 = 整组回滚；函数里不做降级与重试（那是引擎的事）。
     * 事务边界由函数自己持有。
     */
    @FunctionalInterface
    public interface Flush<T, R> {

        /**
         * @param group 同组的一批件（非空，shardKey 相同），顺序 = 入队顺序
         * @return 与 {@code group} 一一对应的逐件业务结果
         */
        List<R> flush(List<T> group);
    }

    private final String name;
    private final Function<T, String> shardKey;
    private final Flush<T, R> flush;

    public MergeStrategy(String name, Function<T, String> shardKey, Flush<T, R> flush) {
        this.name = Objects.requireNonNull(name, "MergeStrategy.name 不能为 null");
        this.shardKey = Objects.requireNonNull(shardKey, "MergeStrategy.shardKey 不能为 null");
        this.flush = Objects.requireNonNull(flush, "MergeStrategy.flush 不能为 null");
    }

    public String getName() {
        return name;
    }

    public String getShardKey(T item) {
        String key = shardKey.apply(item);
        if (key == null) {
            throw new IllegalArgumentException("[合并策略 " + name + "] shardKey 不能为 null："
                    + "分片路由与分组都锚在它上，空键会让所有件挤进同一组");
        }
        return key;
    }

    public Flush<T, R> getFlush() {
        return flush;
    }

    @Override
    public String toString() {
        return "MergeStrategy[" + name + "]";
    }
}
