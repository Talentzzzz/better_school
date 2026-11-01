package com.betterschool.infrastructure.adapter.port.file;

import com.betterschool.domain.file.adapter.port.IFileStoragePort;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * MinIO 对象存储实现：启动时建桶 + 公开读策略（失败不阻断启动，首次上传时重试）；
 * 访问 URL = publicUrl（默认 endpoint）+ bucket + objectKey，桶公开读保证浏览器可直接加载图片
 */
@Slf4j
@Component
public class FileStoragePort implements IFileStoragePort {

    private static final DateTimeFormatter MONTH_DIR = DateTimeFormatter.ofPattern("yyyyMM");

    private final MinioProperties minioProperties;
    private final MinioClient minioClient;

    public FileStoragePort(MinioProperties minioProperties) {
        this.minioProperties = minioProperties;
        this.minioClient = MinioClient.builder()
                .endpoint(minioProperties.getEndpoint())
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .build();
    }

    private volatile boolean bucketReady = false;

    @PostConstruct
    public void initBucket() {
        try {
            ensureBucket();
        } catch (Exception e) {
            // 测试环境无 MinIO 容器 / compose 未起时静默降级：首次上传时再尝试
            log.warn("MinIO 存储桶初始化失败（首次上传时重试）：{}", e.getMessage());
        }
    }

    @Override
    public String store(byte[] content, String extension, String contentType) {
        try {
            ensureBucket();
            String objectKey = "uploads/" + LocalDateTime.now().format(MONTH_DIR) + "/" + UUID.randomUUID() + "." + extension;
            try (InputStream in = new ByteArrayInputStream(content)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(minioProperties.getBucket())
                        .object(objectKey)
                        .stream(in, content.length, -1)
                        .contentType(contentType)
                        .build());
            }
            return publicUrl(objectKey);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("MinIO 存储文件失败", e);
            throw new BusinessException(ResponseCode.SYSTEM_ERROR, "文件上传失败，请稍后重试");
        }
    }

    /** 建桶（幂等）+ 公开读策略；成功后本进程内跳过重复检查 */
    private synchronized void ensureBucket() throws Exception {
        if (bucketReady) {
            return;
        }
        String bucket = minioProperties.getBucket();
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        String policy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": {"AWS": ["*"]},
                      "Action": ["s3:GetObject"],
                      "Resource": ["arn:aws:s3:::%s/*"]
                    }
                  ]
                }
                """.formatted(bucket);
        minioClient.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucket).config(policy).build());
        bucketReady = true;
    }

    private String publicUrl(String objectKey) {
        String base = minioProperties.getPublicUrl();
        if (base == null || base.isBlank()) {
            base = minioProperties.getEndpoint();
        }
        return base + "/" + minioProperties.getBucket() + "/" + objectKey;
    }
}
