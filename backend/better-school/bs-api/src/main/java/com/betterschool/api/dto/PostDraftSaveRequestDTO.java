package com.betterschool.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 帖子草稿保存/编辑请求：字段均可缺省（私有停靠内容，提交进待审时才校验正文必填）
 */
@Data
public class PostDraftSaveRequestDTO {

    @Size(max = 50, message = "标题最长 50 个字符")
    private String title;

    @Size(max = 5000, message = "正文最长 5000 个字符")
    private String content;

    @Size(max = 9, message = "图片最多 9 张")
    private List<String> images;

    /** 独立封面（可空：不传回退相册第一张，均无即无封面帖；可传相册外的独立上传图） */
    @Size(max = 500, message = "封面 URL 最长 500 个字符")
    private String cover;
}
