package com.betterschool.domain.idle.model.valobj;

import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;

/**
 * 商品 feed 游标（market-feed-uv 票 04；uuid-biz-id 票 05 起热度档带时间收敛键）：keyset 分页的续页锚点，
 * 封装 {@code (排序键, [create_time], product_id)}——下一页查询即
 * {@code WHERE (排序键, [create_time], product_id) < / > (?, ?[, ?])}。排序键随 sort 而异：
 * hot = view_count、time / time_asc = feed_time、price_asc / price_desc = price；
 * product_id 是同值并列的 tie-break（与索引尾列一致）。
 *
 * <p>热度档三段收敛（uuid-biz-id 票 05）：业务 ID 是 UUID、无序，view_count 同值时按
 * {@code create_time DESC, product_id DESC} 收敛成确定序，游标因此必须同时携带 create_time 与
 * product_id，否则翻页会在同值段重复/漏行。
 *
 * <p>对外是不透明串（Base64url），内部 {@code product|sort|排序键|create_time|product_id}（5 段，
 * 该档用不到的 create_time 格留空串；照帖子 {@code FeedCursorVO} 先例，各域各写一份）。换 tab 复用旧
 * 游标 / 跨域复用（帖子域游标） / 篡改内容都会在解码时报 PARAM_ERROR，而不是静默错位。游标失效
 * （排序键值已漂移）不报错——keyset 语义下最多边界附近的行有出入，不重不漏由尾段唯一锚点保证。
 */
public final class ProductFeedCursorVO {

    private final String sortType;
    private final LocalDateTime feedTime;
    private final BigDecimal price;
    private final Long viewCount;
    private final LocalDateTime createTime;
    private final String productId;

    public ProductFeedCursorVO(String sortType, LocalDateTime feedTime, BigDecimal price, Long viewCount,
                               LocalDateTime createTime, String productId) {
        this.sortType = sortType;
        this.feedTime = feedTime;
        this.price = price;
        this.viewCount = viewCount;
        this.createTime = createTime;
        this.productId = productId;
    }

    public String sortType() {
        return sortType;
    }

    public LocalDateTime feedTime() {
        return feedTime;
    }

    public BigDecimal price() {
        return price;
    }

    public Long viewCount() {
        return viewCount;
    }

    public LocalDateTime createTime() {
        return createTime;
    }

    public String productId() {
        return productId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProductFeedCursorVO other)) return false;
        return Objects.equals(sortType, other.sortType)
                && Objects.equals(feedTime, other.feedTime)
                && Objects.equals(price, other.price)
                && Objects.equals(viewCount, other.viewCount)
                && Objects.equals(createTime, other.createTime)
                && Objects.equals(productId, other.productId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sortType, feedTime, price, viewCount, createTime, productId);
    }

    @Override
    public String toString() {
        return "ProductFeedCursorVO[sortType=" + sortType + ", feedTime=" + feedTime + ", price=" + price
                + ", viewCount=" + viewCount + ", createTime=" + createTime + ", productId=" + productId + "]";
    }

    /** 热度（浏览 UV 数降序） */
    public static final String SORT_HOT = "hot";
    /** 最新（feed_time 降序，索引正序反向扫描） */
    public static final String SORT_TIME = "time";
    /** 最早（feed_time 升序，同索引正向扫描） */
    public static final String SORT_TIME_ASC = "time_asc";
    public static final String SORT_PRICE_ASC = "price_asc";
    public static final String SORT_PRICE_DESC = "price_desc";

    private static final DateTimeFormatter FEED_TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String SEPARATOR = "|";
    /** 编码字段数：domain | sort | 排序键 | create_time（仅热度档携带） | product_id */
    private static final int PARTS = 5;
    /** 域标签：游标串里钉住来源域，跨域复用（闲置游标喂给帖子列表）在解码时即被拒 */
    private static final String DOMAIN = "product";

    /**
     * 排序口径归一：未知值一律按最新（与切索引前的宽容默认一致，含 {@code time_desc} 这类同义拼写——
     * 它落 default 分支即等于「时间降序」）
     */
    public static String normalizeSort(String sort) {
        if (sort == null) {
            return SORT_TIME;
        }
        return switch (sort) {
            case SORT_HOT, SORT_TIME, SORT_TIME_ASC, SORT_PRICE_ASC, SORT_PRICE_DESC -> sort;
            default -> SORT_TIME;
        };
    }

    /** 首页锚点（无游标入参时） */
    public static ProductFeedCursorVO firstPage(String sortType) {
        return new ProductFeedCursorVO(sortType, null, null, null, null, null);
    }

    /** 从索引扫描行取续页锚点：排序键随 sort 而异，热度档另带 create_time 收敛键，product_id 收并列 */
    public static ProductFeedCursorVO anchorOf(String sortType, ProductFeedIndexItemVO row) {
        return switch (sortType) {
            case SORT_HOT -> new ProductFeedCursorVO(sortType, null, null, (long) row.getViewCount(),
                    row.getCreateTime(), row.getProductId());
            case SORT_PRICE_ASC, SORT_PRICE_DESC -> new ProductFeedCursorVO(sortType, null, row.getPrice(), null,
                    null, row.getProductId());
            default -> new ProductFeedCursorVO(sortType, row.getFeedTime(), null, null, null, row.getProductId());
        };
    }

    /** 是否首页锚点（无 (排序键, product_id) 下界） */
    public boolean isFirstPage() {
        return productId == null;
    }

    public String encode() {
        String key = switch (sortType) {
            case SORT_HOT -> String.valueOf(viewCount);
            case SORT_PRICE_ASC, SORT_PRICE_DESC -> price.toPlainString();
            default -> FEED_TIME_FORMAT.format(feedTime);
        };
        String raw = DOMAIN + SEPARATOR + sortType + SEPARATOR + key + SEPARATOR
                + (SORT_HOT.equals(sortType) ? FEED_TIME_FORMAT.format(createTime) : "") + SEPARATOR
                + productId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 解码并校验来源域与本次请求的排序一致；格式非法 / 跨域 / 排序不符 / 字段非法一律 PARAM_ERROR */
    public static ProductFeedCursorVO decode(String cursor, String expectedSort) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "无效的游标");
        }
        String[] parts = raw.split("\\|", -1);
        if (parts.length != PARTS || !DOMAIN.equals(parts[0]) || !parts[1].equals(expectedSort)
                || parts[2].isEmpty() || (SORT_HOT.equals(expectedSort) && parts[3].isEmpty())) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "无效的游标");
        }
        try {
            String productId = parts[4];
            return switch (expectedSort) {
                case SORT_HOT -> new ProductFeedCursorVO(expectedSort, null, null, Long.parseLong(parts[2]),
                        LocalDateTime.parse(parts[3], FEED_TIME_FORMAT), productId);
                case SORT_PRICE_ASC, SORT_PRICE_DESC -> new ProductFeedCursorVO(expectedSort, null,
                        new BigDecimal(parts[2]), null, null, productId);
                default -> new ProductFeedCursorVO(expectedSort,
                        LocalDateTime.parse(parts[2], FEED_TIME_FORMAT), null, null, null, productId);
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "无效的游标");
        }
    }
}
