package com.betterschool.types.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发帖返利任务消息（topic {@code bs-post-rebate}）。
 * <p>
 * 这条消息不是广播的「帖子已发布」事件，而是专门给返利兜底用的任务：
 * 发帖事务里以 {@code PENDING} 落一行 outbox，提交后先同步调返利；同步成功直接标记完成、不发 MQ。
 * 只有同步失败时才把这行发到本 topic，由消费者重放返利发放（桶唯一键吸收重复）。
 * <p>
 * 只带定位字段，金额与规则由消费方在自己的发放事务里现读——避免生产方读返利规则造成跨域依赖。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostRebateTaskMessage {

    private String postId;
    private String userId;
}
