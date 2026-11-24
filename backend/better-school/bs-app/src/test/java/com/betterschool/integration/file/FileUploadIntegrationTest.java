package com.betterschool.integration.file;

import com.betterschool.integration.support.PostApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片上传通道集成测试：MinIO 容器由基类统一拉起（见 PostApiTestBase#MINIO），
 * 断言外部可观察状态：HTTP 响应与 MinIO 可直接 GET
 */
class FileUploadIntegrationTest extends PostApiTestBase {

    /** 1x1 PNG（真实字节，便于下载断言） */
    private static final byte[] PNG_BYTES = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    // ---------- 上传 ----------

    @Test
    void 未登录上传被拒() {
        ResponseEntity<String> response = upload(PNG_BYTES, "a.png", MediaType.IMAGE_PNG_VALUE, null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void png上传成功且URL可直接下载() {
        Session user = registerAndLogin("13800001001", "上传用户");
        ResponseEntity<String> response = upload(PNG_BYTES, "a.png", MediaType.IMAGE_PNG_VALUE, user);
        String url = assertUploadOk(response);

        ResponseEntity<byte[]> download = restTemplate.getForEntity(url, byte[].class);
        assertThat(download.getStatusCode().value()).isEqualTo(200);
        assertThat(download.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(download.getBody()).isEqualTo(PNG_BYTES);
    }

    @Test
    void jpg与webp上传成功() {
        Session user = registerAndLogin("13800001002", "上传用户2");
        assertThat(assertUploadOk(upload(PNG_BYTES, "b.jpg", MediaType.IMAGE_JPEG_VALUE, user))).isNotBlank();
        assertThat(assertUploadOk(upload(PNG_BYTES, "c.webp", "image/webp", user))).isNotBlank();
    }

    @Test
    void 不支持的扩展名被拒() {
        Session user = registerAndLogin("13800001003", "上传用户3");
        ResponseEntity<String> response = upload(PNG_BYTES, "d.gif", "image/gif", user);
        assertThat(readTree(response.getBody()).path("code").asText()).isEqualTo("400");
    }

    @Test
    void 扩展名与内容类型不符被拒() {
        Session user = registerAndLogin("13800001004", "上传用户4");
        ResponseEntity<String> response = upload(PNG_BYTES, "e.png", "image/gif", user);
        assertThat(readTree(response.getBody()).path("code").asText()).isEqualTo("400");
    }

    @Test
    void 超过5MB被拒() {
        Session user = registerAndLogin("13800001005", "上传用户5");
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        ResponseEntity<String> response = upload(big, "big.jpg", MediaType.IMAGE_JPEG_VALUE, user);
        assertThat(readTree(response.getBody()).path("code").asText()).isEqualTo("400");
    }

    // ---------- 工具 ----------

    private ResponseEntity<String> upload(byte[] bytes, String filename, String contentType, Session session) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(resource, partHeaders));

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (session != null) {
            requestHeaders.setBearerAuth(session.token());
        }
        return restTemplate.postForEntity("/api/files/upload", new HttpEntity<>(body, requestHeaders), String.class);
    }

    private String assertUploadOk(ResponseEntity<String> response) {
        JsonNode body = readTree(response.getBody());
        assertSuccess(body, "上传");
        String url = body.path("data").path("url").asText();
        assertThat(url).as("上传应返回 URL：%s", body).isNotBlank();
        return url;
    }
}
