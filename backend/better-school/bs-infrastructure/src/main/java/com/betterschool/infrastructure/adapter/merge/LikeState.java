package com.betterschool.infrastructure.adapter.merge;

/**
 * 点赞类动作的逐件业务结果——落库方法回填给等待者的东西。
 *
 * <p><b>{@code DUPLICATE} 与 {@code NOT_FOUND} 是业务结果，不是异常</b>：批内部分撞唯一键时，
 * {@code INSERT IGNORE} 的 affected 只给行数，落库方法回查该批 {@code (targetId, userId)} 后
 * 只把 {@code DUPLICATE} 回给命中的那几件，其余照常成功（spec §3.1）。
 *
 * <p>技术性失败（事务回滚、连接断）不走这里，走 {@link MergeState#FAILED}。
 *
 * <p><b>浏览类动作（{@code POST_VIEW} / {@code PRODUCT_VIEW}）没有逐件业务结果</b>：落库方法统一回
 * {@link #APPLIED} 当占位值，含义是「计数已推进」。引擎只用它判断「这一件被回填了」，不会把它翻成对外
 * 的业务结果（浏览入口 {@code submitAsync} 根本没有人等结果）；点赞路径若拿到它，
 * {@code CountMergeWriter} 会直接抛——占位值不允许混进等结果的那条路。
 */
public enum LikeState {

    /** 关系行已写入（或已删除）且计数已同事务推进 */
    LIKED,

    /** 取消赞：关系行已删除 */
    UNLIKED,

    /** 撞唯一键：已经赞过（或已经处理过），计数未重复推进 */
    DUPLICATE,

    /** 取消的赞本来就不存在：语义是「已经是未赞状态」 */
    NOT_FOUND,

    /** 浏览类动作的占位结果：本组的 UV 明细 + 加速列已落（没有人等浏览结果，也就没有「已赞/重复」可分） */
    APPLIED
}
