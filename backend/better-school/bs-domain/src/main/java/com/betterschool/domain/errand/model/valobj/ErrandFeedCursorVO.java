package com.betterschool.domain.errand.model.valobj;

import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * 跑腿大厅游标（market-feed-uv 票 04；uuid-biz-id 票 05 起热度档带 create_time 收敛键）：
 * keyset 分页的续页锚点，封装 {@code (排序键, errand_order_id)}——下一页查询即
 * {@code WHERE (排序键, errand_order_id) < / > (?, ?)}。排序键随 sort 而异：
 * hot = view_count、time / time_asc = feed_time、price_asc / price_desc = 悬赏（索引列名 price）：
 * errand_order_id 是同值并列的 tie-break（与索引尾列一致）。
 *
 * <p>热度档（uuid-biz-id 票 05）：业务 ID 是 UUID 无序，「浏览量同值」不能再靠 errand_order_id 降序
 * 表达「创建先后」，同值收敛键换成 create_time 快照（{@code view_count DESC, create_time DESC,
 * errand_order_id DESC}），游标因此必须同时携带 view_count 与 create_time，否则翻页会在同值段
 * 重复/漏行。
 *
 * <p>对外是不透明串（Base64url），内部 {@code errand|排序键|业务ID}；由域内编码解码（照帖子
 * {@code FeedCursorVO} 先例，各域各写一份）。换 tab 复用旧游标 / 跨域复用（闲置游标） / 篡改内容
 * 都会在解码时报 PARAM_ERROR，而不是静默错位。游标失效（排序键值已漂移）不报错——keyset 语义下
 * 最多边界附近的行有出入，不重不漏由 {@code errand_order_id} 唯一锚点保证。
 */
public record ErrandFeedCursorVO(String sortType, LocalDateTime feedTime, LocalDateTime createTime,
                                 BigDecimal price, Long viewCount, String orderId) {

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
    /** 域标签：游标串里钉住来源域，跨域复用（跑腿游标喂给闲置推荐页）在解码时即被拒 */
    private static final String DOMAIN = "errand";
    /** 编码字段数：errand | sort | 排序键 | create_time（仅热度档用，其余留空） | orderId */
    private static final int PARTS = 5;

    /** 排序口径归一：未知值一律按最新（大厅切索引前只有时间降序一档，含 {@code time_desc} 这类同义拼写） */
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
    public static ErrandFeedCursorVO firstPage(String sortType) {
        return new ErrandFeedCursorVO(sortType, null, null, null, null, null);
    }

    /** 从索引扫描行取续页锚点：排序键随 sort 而异（热度档 view_count + create_time），errand_order_id 收并列 */
    public static ErrandFeedCursorVO anchorOf(String sortType, ErrandFeedIndexItemVO row) {
        return switch (sortType) {
            case SORT_HOT -> new ErrandFeedCursorVO(sortType, null, row.getCreateTime(), null,
                    (long) row.getViewCount(), row.getOrderId());
            case SORT_PRICE_ASC, SORT_PRICE_DESC -> new ErrandFeedCursorVO(sortType, null, null, row.getPrice(),
                    null, row.getOrderId());
            default -> new ErrandFeedCursorVO(sortType, row.getFeedTime(), null, null, null, row.getOrderId());
        };
    }

    /** 是否首页锚点（无 (排序键, errand_order_id) 下界） */
    public boolean isFirstPage() {
        return orderId == null;
    }

    public String encode() {
        String key = switch (sortType) {
            case SORT_HOT -> String.valueOf(viewCount);
            case SORT_PRICE_ASC, SORT_PRICE_DESC -> price.toPlainString();
            default -> FEED_TIME_FORMAT.format(feedTime);
        };
        // 热度档第二收敛键 = create_time 快照；其余档该格留空（与帖子 FeedCursorVO 的留空格同款）
        String secondary = SORT_HOT.equals(sortType) ? FEED_TIME_FORMAT.format(createTime) : "";
        String raw = DOMAIN + SEPARATOR + sortType + SEPARATOR + key + SEPARATOR + secondary + SEPARATOR + orderId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 解码并校验来源域与本次请求的排序一致；格式非法 / 跨域 / 排序不符 / 字段非法一律 PARAM_ERROR */
    public static ErrandFeedCursorVO decode(String cursor, String expectedSort) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "无效的游标");
        }
        String[] parts = raw.split("\\|", -1);
        if (parts.length != PARTS || !DOMAIN.equals(parts[0]) || !parts[1].equals(expectedSort)) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "无效的游标");
        }
        try {
            String orderId = parts[4];
            if (orderId.isEmpty()) {
                throw new BusinessException(ResponseCode.PARAM_ERROR, "无效的游标");
            }
            return switch (expectedSort) {
                case SORT_HOT -> {
                    if (parts[2].isEmpty() || parts[3].isEmpty()) {
                        throw new BusinessException(ResponseCode.PARAM_ERROR, "无效的游标");
                    }
                    yield new ErrandFeedCursorVO(expectedSort, null,
                            LocalDateTime.parse(parts[3], FEED_TIME_FORMAT), null,
                            Long.parseLong(parts[2]), orderId);
                }
                case SORT_PRICE_ASC, SORT_PRICE_DESC -> {
                    if (parts[2].isEmpty()) {
                        throw new BusinessException(ResponseCode.PARAM_ERROR, "无效的游标");
                    }
                    yield new ErrandFeedCursorVO(expectedSort, null, null,
                            new BigDecimal(parts[2]), null, orderId);
                }
                default -> {
                    if (parts[2].isEmpty()) {
                        throw new BusinessException(ResponseCode.PARAM_ERROR, "无效的游标");
                    }
                    yield new ErrandFeedCursorVO(expectedSort,
                            LocalDateTime.parse(parts[2], FEED_TIME_FORMAT), null, null, null, orderId);
                }
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "无效的游标");
        }
    }
}
