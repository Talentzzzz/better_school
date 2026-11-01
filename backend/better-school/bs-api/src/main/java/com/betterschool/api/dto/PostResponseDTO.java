package com.betterschool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class PostResponseDTO {

    private String postId;
    private String userId;
    private String nickname;
    private String avatar;
    private String title;
    private String content;
    private List<String> images;
    /** 展示封面（独立封面 ?? 相册第一张，均无 null） */
    private String cover;
    /** 原始独立封面（未回退相册，可空）：编辑回填用，草稿编辑页以此区分"未指定封面"与"兜底到相册第一张" */
    private String independentCover;
    private String status;
    /** 拒绝理由：仅被拒帖对作者本人返回（他人/其他状态不带） */
    private String rejectReason;
    private long likeCount;
    private long commentCount;
    /** 浏览量：仅作者本人可见（访客为 null） */
    private Long viewCount;
    private boolean liked;
    private LocalDateTime createdAt;
}
