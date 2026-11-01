package com.betterschool.domain.community.model.valobj;

import com.betterschool.domain.community.model.entity.PostEntity;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 主页 feed 一页的读取结果（feed-index-uv 票 04）：帖子列表 + 续页游标 + 是否走了索引路径。
 *
 * <ul>
 *   <li>{@code nextCursor} 非 null：下一页必须带它续页（keyset）；null = 未知或没有下一页，
 *       调用方按 page 续页（降级路径）或以「本页不满页」收尾</li>
 *   <li>{@code indexPath}：索引路径成功时 true——total 降口径（不再精确 COUNT，调用方置 -1）；
 *       false = 降级直查 / 分类过滤路径，total 仍按精确 COUNT 出</li>
 * </ul>
 */
@Data
@AllArgsConstructor
public class FeedPageVO {

    private List<PostEntity> posts;

    private String nextCursor;

    private boolean indexPath;
}
