package com.betterschool.types.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发帖返利领取任务消息（topic {@code bs-post-rebate-claim}）。
 * <p>
 * 发帖事务内以 {@code PENDING} 落一行 outbox，提交后由 case 立刻同步领取；正常返回后任务标记已处理，
 * 只有同步领取抛异常时任务才留 PENDING，由 outbox 扫描重发到本 topic 重放。
 * <p>
 * {@code periodKey} 在发帖事务里按数据库发布时间定死（{@code yyyyMMdd}，Asia/Shanghai），
 * 重放必须沿用它而不是重放当天的日期，避免跨零点重复占一个周期名额。旧载荷可能缺该字段，
 * 重放方按当天兜底。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostRebateClaimTaskMessage {

    private String postId;
    private String userId;
    private String periodKey;
}
