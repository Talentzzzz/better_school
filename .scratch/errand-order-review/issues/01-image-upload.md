Status: resolved
Type: task
Blocked by:

# 01: MinIO 图片上传通道 + 订单配图字段

**What to build:** 为全站补上真正的图片上传通道（当前图片只是 URL 字符串手填，无任何上传代码）。docker-compose 新增 MinIO 服务；后端引入 MinIO SDK，新增上传接口 `POST /api/files/upload`（仅登录用户，jpg/png/webp、单张 ≤5MB），文件入 MinIO bucket，返回后端签发的访问 URL。`errand_order` 表新增 `images` 字段（URL 列表，沿用 `post.images` 的存储约定，上限 6 张由创建链路校验，本票只做字段与校验常量）。前端 `ImageUrlPicker.vue` 从手填 URL 改为真上传。增量 ALTER 脚本供联调库，init.sql 原地更新。

**Status:** done

- [x] docker-compose 有 MinIO 服务，compose 起来后上传接口真实可用
- [x] 非 jpg/png/webp、超 5MB、未登录分别被拒
- [x] `errand_order.images` 字段就绪，ALTER 脚本可执行
- [x] 前端选图即上传、失败有反馈，vue-tsc 通过

## Answer

实现要点（票 01）：

- **上传通道**：`POST /api/files/upload`（multipart，仅登录用户）。分层：`IFileService`（api 契约）→ `FileController`（trigger）→ `FileUploadCase`（case，白名单 jpg/jpeg/png/webp + Content-Type 一致性 + 5MB 校验，常量在 `Constants`）→ `IFileStoragePort`（domain 端口）→ `FileStoragePort`（infrastructure，MinIO SDK）。启动/首次上传自动建桶 + 公开读策略，URL = `minio.public-url`/bucket/`uploads/yyyyMM/uuid.ext`。
- **配图字段**：`errand_order.images VARCHAR(1000)` JSON 数组（沿用 post.images 约定），DTO `@Size(max=6)` + case 层 `Constants.ERRAND_MAX_IMAGES` 双重校验，打通 DTO→case→domain→PO→mapper 全链路；增量脚本 `sql/migrations/005_errand_order_images.sql`，init.sql 原地更新。
- **前端**：`ImageUrlPicker.vue` 手填 URL 改为真上传（类型/大小预检、上传中态、失败 toast），`ErrandPublishView` 接入 `:max="6"`；`api/files.ts` 新增。
- **测试**：`FileUploadIntegrationTest`（9 例，Testcontainers 真 MinIO 容器；容器放 `CommunityApiTestBase` 基类——放子类会分裂第二个 Spring 上下文导致 xxl-job handler 注册冲突）+ 浏览器端到端冒烟（上传→发布→落库→图片 URL 公开可读）。后端全量 49/49 绿，vue-tsc 通过。
- compose 中 xxl-job 的 `restart: always` 移除是 spec 期已有改动，随本票一并入库。
