package com.betterschool.domain.community.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 点赞 / 取消赞这类「写关系行」动作的业务结果——合并器落库后回给域服务的东西。
 *
 * <p><b>{@code DUPLICATE} 与 {@code NOT_FOUND} 都是业务结果，不是异常</b>：它们是「这一笔没有改变
 * 任何行」的两种情形（已经赞过 / 本来就没赞），由唯一键与影响行数裁决。把它们做成返回值而不是
 * 异常，是因为一笔撞键不该让同组其他笔一起回滚——组内 N 笔里只有命中的那几笔拿这两个结果。
 *
 * <p>技术性失败（事务回滚、等超时、缓冲满且同步降级也失败）不走这里：实现层直接回
 * {@code SYSTEM_ERROR}（「系统繁忙，请稍后重试」），调用方可安全重试——重试的重复赞由唯一键吸收。
 */
@Getter
@AllArgsConstructor
public enum LikeStateVO {

    /** 关系行已写入且计数已同事务推进 */
    LIKED("已点赞"),

    /** 取消赞：关系行已删除且计数已同事务回落 */
    UNLIKED("已取消点赞"),

    /** 已经赞过（唯一键裁决）：本次没有新增关系行，计数未重复推进 */
    DUPLICATE("已经点过赞"),

    /** 取消的赞本来就不存在：语义是「已经是未赞状态」 */
    NOT_FOUND("本来就没点赞");

    private final String desc;
}
