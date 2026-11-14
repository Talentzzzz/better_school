package com.betterschool.domain.chat.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 增量补拉一页：条目 + 游标 + 是否还有更多。
 * <p>
 * 游标取「本次扫描边界」（本页最后一行的 delivery_seq），不是「最后一条拼装成功的条目」：
 * 扫描窗内有正文缺失的漂移行被跳过时，游标照样越过它，不会停在它前面
 * （连续 ≥ limit 行漂移会让旧口径的游标永久停滞，此后新消息一条都拉不到）。
 * <p>
 * hasMore 是显式契约：前端不再拿「返回条数 &lt; limit」推断追平——那个推断在有任何过滤
 * （漂移行、未来的清理/黑名单）时会失真。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimelinePageVO {

    private List<TimelineItemVO> items;

    /** 下次补拉起点：本次扫描到的最大 delivery_seq；无投递行时原样回传入参 */
    private Long nextCursor;

    /** true = 本次扫描填满一页，可能还有，需继续拉 */
    private Boolean hasMore;
}
