package com.betterschool.infrastructure.adapter.port.file;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MinIO 连接配置（对齐 docker-compose bs-minio）
 */
@Data
@Component
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

    /** S3 API 地址 */
    private String endpoint;

    private String accessKey;

    private String secretKey;

    /** 存储桶名 */
    private String bucket;

    /** 浏览器访问 URL 前缀（默认取 endpoint） */
    private String publicUrl;
}
