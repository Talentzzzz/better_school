package com.betterschool.api.dto;

import lombok.Data;

@Data
public class FileUploadResponseDTO {

    /** 可公开访问的图片 URL */
    private String url;
}
