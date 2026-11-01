package com.betterschool.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PostCommentRequestDTO {

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 500, message = "评论最长 500 个字符")
    private String content;
}
