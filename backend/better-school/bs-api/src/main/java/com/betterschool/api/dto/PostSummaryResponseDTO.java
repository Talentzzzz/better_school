package com.betterschool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 帖子列表摘要 DTO（列表页最小字段集）：
 * 访客视角不暴露浏览量（viewCount=null），作者"我的"接口返回浏览量
 */
@Data
public class PostSummaryResponseDTO {

    private String postId;
    private String title;
    /** 封面（独立封面 ?? 相册第一张，均无 null = 无封面帖） */
    private String cover;
    private String userId;
    private String nickname;
    private String avatar;
    private long likeCount;
    private LocalDateTime createdAt;
    /** 仅"我的"接口（作者视角）填充，访客列表为 null */
    private Long viewCount;
    /** 仅"我的"接口填充（NORMAL/DRAFT），访客列表为 null */
    private String status;
    /** 登录用户是否已点赞（未登录 null）；"我的"接口恒 true */
    private Boolean liked;
    /** 登录用户是否浏览过（feed-index-uv 票 05 已读灰显标记）：仅主页 feed 接口填充，其余列表 null（未登录 false） */
    private Boolean viewed;
    /** 正文："我的"列表填充全量（客户端 contains 过滤搜索用）；主页 feed 填充截断摘要（约前 50 字），其余列表 null */
    private String content;
}
