# 文件模块接口

## POST /api/files/upload

图片上传（全站共用通道，MinIO 存储）

- Auth: 需要登录（不在 SecurityConfig 白名单，POST 默认鉴权）
- Body: multipart/form-data，字段 `file`
- 限制: jpg/png/webp；单张 ≤5MB（`Constants.UPLOAD_IMAGE_MAX_BYTES`，multipart 容器上限 6MB 兜底）
- 成功: `{ url }` —— MinIO 公开读 URL（`{minio.public-url}/{bucket}/{objectKey}`），可直接存入 post/product 的 images 列表
- 失败常见:
  - 未登录 → HTTP 401（SecurityConfig 统一 JSON）
  - 非 jpg/png/webp、Content-Type 与扩展名不符、>5MB、文件为空 → code 400
  - MinIO 不可用 → code 999（"文件上传失败"）
- 流程: `FileController`（trigger）读 multipart → 直调 `infrastructure/file/FileStorage`（技术组件，白名单/大小校验在组件内）→ MinIO putObject 到 `uploads/yyyyMM/uuid.ext`，桶与公开读策略在启动/首次上传时自动保证。技术能力不占 domain 段，故无 case 编排与 domain 端口
