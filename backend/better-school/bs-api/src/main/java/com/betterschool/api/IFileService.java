package com.betterschool.api;

import com.betterschool.api.dto.FileUploadResponseDTO;
import com.betterschool.api.response.Response;

/**
 * 文件服务契约：HTTP 层由 Controller 实现，后续微服务化时由 Dubbo Provider 实现
 */
public interface IFileService {

    /**
     * 上传图片（multipart，字段名 file）
     *
     * @param content     文件字节
     * @param fileName    原始文件名（取扩展名判断类型）
     * @param contentType 文件 MIME 类型
     * @return 可公开访问的 URL
     */
    Response<FileUploadResponseDTO> uploadImage(byte[] content, String fileName, String contentType);
}
