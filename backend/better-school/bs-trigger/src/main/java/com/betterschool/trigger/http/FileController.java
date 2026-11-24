package com.betterschool.trigger.http;

import com.betterschool.api.IFileService;
import com.betterschool.api.dto.FileUploadResponseDTO;
import com.betterschool.api.response.Response;
import com.betterschool.infrastructure.file.FileStorage;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 文件 HTTP 入口：/api/files/upload 不在白名单，POST 默认需登录（SecurityConfig anyRequest）；
 * 类型/大小白名单校验在 infrastructure/file/FileStorage（技术组件，非业务用例，无 case 编排）
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController implements IFileService {

    private final FileStorage fileStorage;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<FileUploadResponseDTO> upload(@RequestParam("file") MultipartFile file) {
        return uploadImage(getBytes(file), file.getOriginalFilename(), file.getContentType());
    }

    @Override
    public Response<FileUploadResponseDTO> uploadImage(byte[] content, String fileName, String contentType) {
        String url = fileStorage.uploadImage(content, fileName, contentType);
        FileUploadResponseDTO dto = new FileUploadResponseDTO();
        dto.setUrl(url);
        return Response.ok(dto);
    }

    private byte[] getBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "文件读取失败，请重试");
        }
    }
}
