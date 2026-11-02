package com.betterschool.integration.file;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片上传通道集成测试：MinIO 容器由基类统一拉起（见 CommunityApiTestBase#MINIO），
 * 断言外部可观察状态：HTTP 响应、MinIO 可直接 GET、errand_order.images 落库与详情返回
 */
class FileUploadIntegrationTest extends CommunityApiTestBase {

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

    // ---------- 跑腿订单配图 ----------

    @Test
    void 订单带6张配图创建成功并落库返回() {
        Session user = registerAndLogin("13800001006", "发单用户");
        List<String> images = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            images.add(assertUploadOk(upload(PNG_BYTES, "p" + i + ".png", MediaType.IMAGE_PNG_VALUE, user)));
        }
        String orderId = createErrand(user, images);

        String imagesJson = jdbcTemplate.queryForObject(
                "SELECT images FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
        assertThat(imagesJson).isNotNull();
        assertThat(readTree(imagesJson).size()).isEqualTo(6);

        JsonNode detail = getJson("/api/errand/orders/" + orderId, user);
        assertSuccess(detail, "订单详情");
        assertThat(detail.path("data").path("images").size()).isEqualTo(6);
        assertThat(detail.path("data").path("images").get(0).asText()).isEqualTo(images.get(0));
    }

    @Test
    void 订单7张配图被拒() {
        Session user = registerAndLogin("13800001007", "发单用户7");
        List<String> images = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            images.add("http://localhost:9000/bs-files/uploads/x" + i + ".png");
        }
        JsonNode response = postJson("/api/errand/orders", errandBody(images), user);
        assertThat(response.path("code").asText()).isEqualTo("400");
    }

    @Test
    void 订单无配图images为空列表() {
        Session user = registerAndLogin("13800001008", "无图用户");
        String orderId = createErrand(user, null);
        String imagesJson = jdbcTemplate.queryForObject(
                "SELECT images FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
        assertThat(imagesJson).isNull();

        JsonNode detail = getJson("/api/errand/orders/" + orderId, user);
        assertSuccess(detail, "订单详情");
        assertThat(detail.path("data").path("images").isEmpty()).isTrue();
    }

    // ---------- 工具 ----------

    private String createErrand(Session user, List<String> images) {
        JsonNode response = postJson("/api/errand/orders", errandBody(images), user);
        assertSuccess(response, "发布跑腿");
        // uuid-biz-id 票 05 起订单 ID 是 UUID 字符串（此文件漏翻，票 08 全量回归抓出）
        return response.path("data").asText();
    }

    private Map<String, Object> errandBody(List<String> images) {
        Map<String, Object> body = Map.ofEntries(
                Map.entry("type", "EXPRESS_PICKUP"),
                Map.entry("description", "帮忙取个快递"),
                Map.entry("pickupLocation", "菜鸟驿站"),
                Map.entry("deliveryLocation", "6号楼楼下"),
                Map.entry("reward", 10),
                Map.entry("timeLimit", "HOURS_2"));
        if (images == null) {
            return body;
        }
        Map<String, Object> withImages = new java.util.HashMap<>(body);
        withImages.put("images", images);
        return withImages;
    }

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
