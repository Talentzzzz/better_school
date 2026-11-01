package com.betterschool.usecase.file;

import com.betterschool.domain.file.adapter.port.IFileStoragePort;
import com.betterschool.types.common.Constants;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 文件上传用例：白名单校验（扩展名 + Content-Type + 大小）通过后交对象存储端口
 */
@Service
@RequiredArgsConstructor
public class FileUploadCase {

    private final IFileStoragePort fileStoragePort;

    /**
     * 上传图片
     *
     * @return 可公开访问的 URL
     */
    public String uploadImage(byte[] content, String fileName, String contentType) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "文件为空");
        }
        if (content.length > Constants.UPLOAD_IMAGE_MAX_BYTES) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "单张图片不能超过 5MB");
        }
        String extension = extensionOf(fileName);
        String expectedContentType = Constants.UPLOAD_IMAGE_CONTENT_TYPES.get(extension);
        if (expectedContentType == null) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "仅支持 jpg/png/webp 图片");
        }
        if (!expectedContentType.equals(contentType)) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "文件内容与扩展名不符");
        }
        return fileStoragePort.store(content, extension, contentType);
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase();
    }
}
