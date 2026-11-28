package com.betterschool.domain.post.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 发布口径的帖子结果对象（直接发布 / 草稿提交共用）：业务 ID + 数据库发布时间。
 *
 * <p>{@code publishTime} 取数据库时钟落库的 {@code post.update_time}——直接发布是插入行的时刻，
 * 草稿提交是条件 UPDATE 翻成 NORMAL 的时刻（不是草稿创建时刻）。发帖返利的限次周期键
 * （{@code periodKey}，日限 {@code yyyyMMdd}）由它定死，跨零点时不会串天。
 *
 * <p>保存 / 编辑草稿不走本对象（草稿不触发返利、不占名额）。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PostPublishResultVO {

    private String postId;

    private LocalDateTime publishTime;
}
