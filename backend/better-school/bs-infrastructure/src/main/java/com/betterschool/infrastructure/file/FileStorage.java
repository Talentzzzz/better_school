package com.betterschool.infrastructure.file;

import com.betterschool.types.common.Constants;
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
import java.util.concurrent.locks.ReentrantLock;

/**
 * 对象存储技术组件（MinIO）：白名单校验（扩展名 + Content-Type + 大小）内聚在组件内，
 * 上传成功返回可直接访问的 URL。
 * <p>
 * 技术能力不占 domain 段（见 CLAUDE.md §1「不得为技术能力开 port」），整体落技术包；
 * 由 trigger 直接注入（同 {@code infrastructure/ws} 待遇，bs-trigger → bs-infrastructure），
 * 仓储/其他技术包若要存文件也从本包取用。
 * <p>
 * 访问 URL = publicUrl（默认 endpoint）+ bucket + objectKey，桶公开读保证浏览器可直接加载图片。
 */
@Slf4j
@Component
public class FileStorage {

    private static final DateTimeFormatter MONTH_DIR = DateTimeFormatter.ofPattern("yyyyMM");

    private final MinioProperties minioProperties;
    private final MinioClient minioClient;

    public FileStorage(MinioProperties minioProperties) {
        this.minioProperties = minioProperties;
        this.minioClient = MinioClient.builder()
                .endpoint(minioProperties.getEndpoint())
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .build();
    }

    private final ReentrantLock bucketLock = new ReentrantLock();
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

    /**
     * 上传图片：校验通过后交 MinIO，返回可公开访问的 URL
     *
     * @param content     文件字节
     * @param fileName    原始文件名（用于取扩展名）
     * @param contentType 请求声明的 MIME 类型
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
        return store(content, extension, contentType);
    }

    private String store(byte[] content, String extension, String contentType) {
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

    /**
     * 建桶（幂等）+ 公开读策略；成功后本进程内跳过重复检查。
     * 用 {@link ReentrantLock} 而非 {@code synchronized}：建桶是 MinIO 网络 I/O，
     * 虚拟线程在监视器上等待会 pin 住载体线程，ReentrantLock 让等待的 VT 正常卸载。
     */
    private void ensureBucket() throws Exception {
        if (bucketReady) {
            return;
        }
        bucketLock.lock();
        try {
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
        } finally {
            bucketLock.unlock();
        }
    }

    private String publicUrl(String objectKey) {
        String base = minioProperties.getPublicUrl();
        if (base == null || base.isBlank()) {
            base = minioProperties.getEndpoint();
        }
        return base + "/" + minioProperties.getBucket() + "/" + objectKey;
    }
}
