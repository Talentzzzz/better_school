package com.betterschool.domain.post.model.valobj;

import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;

/**
 * feed 游标（feed-index-uv 票 04；uuid-biz-id 票 03 起带时间收敛键）：keyset 分页的续页锚点，封装
 * {@code (排序键, feed_time, post_id)}——下一页查询即 {@code WHERE (排序键, feed_time, post_id) < (?, ?, ?)}。
 *
 * <p>排序键随 sort 而异：time = feed_time、hot = like_count；<b>两档都带 feed_time 与 post_id</b>：
 * UUID 业务 ID 无序，「点赞数同值」不再能靠 post_id 降序表达「发布时间先后」，确定序收敛统一换成
 * 「feed_time 降序 + post_id 降序」，游标因此必须同时携带两者，否则翻页会在同值段重复/漏行。
 *
 * <p>对外是不透明串（Base64url），由域内编码解码：换 tab 复用旧游标 / 篡改内容都会在解码时
 * 报 PARAM_ERROR，而不是静默错位。游标失效（排序键值已漂移）不报错——keyset 语义下最多
 * 边界附近的行有出入，不重不漏由 (feed_time, post_id) 唯一锚点保证。
 */
public final class FeedCursorVO {

    private final String sortType;
    private final LocalDateTime feedTime;
    private final Long likeCount;
    private final String postId;

    public FeedCursorVO(String sortType, LocalDateTime feedTime, Long likeCount, String postId) {
        this.sortType = sortType;
        this.feedTime = feedTime;
        this.likeCount = likeCount;
        this.postId = postId;
    }

    public String sortType() {
        return sortType;
    }

    public LocalDateTime feedTime() {
        return feedTime;
    }

    public Long likeCount() {
        return likeCount;
    }

    public String postId() {
        return postId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FeedCursorVO other)) return false;
        return Objects.equals(sortType, other.sortType)
                && Objects.equals(feedTime, other.feedTime)
                && Objects.equals(likeCount, other.likeCount)
                && Objects.equals(postId, other.postId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sortType, feedTime, likeCount, postId);
    }

    @Override
    public String toString() {
        return "FeedCursorVO[sortType=" + sortType + ", feedTime=" + feedTime + ", likeCount=" + likeCount
                + ", postId=" + postId + "]";
    }

    private static final DateTimeFormatter FEED_TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String SEPARATOR = "|";
    private static final String SORT_HOT = "hot";
    /** 编码字段数：sort | like_count | feed_time | post_id（该档用不到的那格留空串） */
    private static final int PARTS = 4;

    /** 首页锚点（无游标入参时） */
    public static FeedCursorVO firstPage(String sortType) {
        return new FeedCursorVO(sortType, null, null, null);
    }

    /** 从索引扫描行取续页锚点：排序键随 sort 而异（time = feed_time、hot = like_count），feed_time + post_id 两档都带 */
    public static FeedCursorVO anchorOf(String sortType, FeedIndexItemVO row) {
        return new FeedCursorVO(sortType,
                row.getFeedTime(),
                SORT_HOT.equals(sortType) ? (long) row.getLikeCount() : null,
                row.getPostId());
    }

    /** 是否首页锚点（无 (排序键, feed_time, post_id) 下界） */
    public boolean isFirstPage() {
        return postId == null;
    }

    public String encode() {
        String raw = sortType + SEPARATOR
                + (likeCount == null ? "" : String.valueOf(likeCount)) + SEPARATOR
                + FEED_TIME_FORMAT.format(feedTime) + SEPARATOR
                + postId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 解码并校验与本次请求的排序一致；格式非法 / 排序不符 / 字段非法一律 PARAM_ERROR */
    public static FeedCursorVO decode(String cursor, String expectedSort) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "无效的游标");
        }
        String[] parts = raw.split("\\|", -1);
        if (parts.length != PARTS || !parts[0].equals(expectedSort) || parts[2].isEmpty() || parts[3].isEmpty()) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "无效的游标");
        }
        try {
            LocalDateTime feedTime = LocalDateTime.parse(parts[2], FEED_TIME_FORMAT);
            return SORT_HOT.equals(expectedSort)
                    ? new FeedCursorVO(expectedSort, feedTime, Long.parseLong(parts[1]), parts[3])
                    : new FeedCursorVO(expectedSort, feedTime, null, parts[3]);
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "无效的游标");
        }
    }
}
