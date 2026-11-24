package com.betterschool.domain.post.model.entity;

import com.betterschool.domain.post.model.valobj.PostStatusVO;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class PostEntity {

    private String postId;
    private String userId;
    private String title;
    private String content;
    private List<String> images;
    /** 独立封面 URL（可空，可传相册外的独立上传图；展示口径见 displayCover） */
    private String cover;
    private PostStatusVO status;
    private long likeCount;
    private long commentCount;
    private long viewCount;
    private LocalDateTime createdAt;

    /** 查询冗余字段：作者昵称/头像（列表 JOIN 填充） */
    private String authorNickname;
    private String authorAvatar;

    /** 是否已点赞（当前用户，详情接口填充） */
    private boolean liked;

    /** 发布（信用分门槛已由 case 层前置）落库即可见 */
    public static PostEntity create(String userId, String title, String content,
                                    List<String> images, String cover) {
        PostEntity postEntity = new PostEntity();
        postEntity.userId = userId;
        postEntity.title = title;
        postEntity.content = content;
        postEntity.images = images == null ? List.of() : images;
        postEntity.cover = normalizedCover(cover);
        postEntity.status = PostStatusVO.NORMAL;
        postEntity.likeCount = 0;
        postEntity.commentCount = 0;
        postEntity.viewCount = 0;
        return postEntity;
    }

    /** 草稿（私有停靠：字段可缺省，正文提交时才校验必填；不做信用分门槛） */
    public static PostEntity createDraft(String userId, String title, String content,
                                         List<String> images, String cover) {
        PostEntity postEntity = new PostEntity();
        postEntity.userId = userId;
        postEntity.title = title;
        postEntity.content = content;
        postEntity.images = images == null ? List.of() : images;
        postEntity.cover = normalizedCover(cover);
        postEntity.status = PostStatusVO.DRAFT;
        postEntity.likeCount = 0;
        postEntity.commentCount = 0;
        postEntity.viewCount = 0;
        return postEntity;
    }

    /** 覆盖草稿内容（全量编辑：文字、图片、封面全部可改） */
    public void applyDraftContent(String title, String content, List<String> images,
                                  String cover) {
        this.title = title;
        this.content = content;
        this.images = images == null ? List.of() : images;
        this.cover = normalizedCover(cover);
    }

    /**
     * 封面展示口径（列表与详情统一）：独立封面优先，相册第一张兜底；无封面且无配图返回 null（即"无封面帖"）。
     * 读时兜底而非落库回退值：编辑相册后封面自动跟随，不产生陈旧回退数据
     */
    public String displayCover() {
        if (cover != null && !cover.isBlank()) {
            return cover;
        }
        return images.isEmpty() ? null : images.get(0);
    }

    private static String normalizedCover(String cover) {
        return cover == null || cover.isBlank() ? null : cover;
    }

    public boolean isOwner(String userId) {
        return this.userId != null && this.userId.equals(userId);
    }
}
