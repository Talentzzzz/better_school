package com.betterschool.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 增量补拉一页：消息列表 + 游标 + 是否还有更多。
 * <p>
 * nextCursor 是「服务端本次扫描边界」，不是最后一条消息的位置——扫描窗内若有漂移行被跳过，
 * 游标照样前进，不会卡在它前面。hasMore 为显式契约：前端不再用「返回条数 &lt; limit」推断追平。
 */
@Data
public class ChatTimelineResponseDTO {

    private List<ChatMessageResponseDTO> list;

    /** 下次补拉起点（游标只前进不回退；无新增投递行时原样回传） */
    private Long nextCursor;

    /** true = 本次填满一页，可能还有，需继续拉 */
    private Boolean hasMore;
}
