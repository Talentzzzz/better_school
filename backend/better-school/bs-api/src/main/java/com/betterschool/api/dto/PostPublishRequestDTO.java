package com.betterschool.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class PostPublishRequestDTO {

    @Size(max = 50, message = "标题最长 50 个字符")
    private String title;

    @NotBlank(message = "正文不能为空")
    @Size(max = 5000, message = "正文最长 5000 个字符")
    private String content;

    @Size(max = 9, message = "图片最多 9 张")
    private List<String> images;

    /** 独立封面（可空：不传回退相册第一张，均无即无封面帖；可传相册外的独立上传图） */
    @Size(max = 500, message = "封面 URL 最长 500 个字符")
    private String cover;
}
