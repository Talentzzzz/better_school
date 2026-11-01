# 13: `ensureBucket` 改 `ReentrantLock`（本版唯一 pinning 必修）

**What to build:** spec §2.4 必修项：`FileStoragePort.ensureBucket()` 的 `synchronized` 包 MinIO 网络 I/O（VT 下 pin 载体线程），改为 `ReentrantLock` + 双检（spec §2.4 / 票 03 §4.3 的改法）。启动期 `@PostConstruct` 已软预热 ⇒ 这是廉价保险而非热点，改法求正确不求性能。修完用 `measure/` 现有资产跑一次**一次性 JFR**（`jdk.VirtualThreadPinned` threshold=0ms 口径）确认该点 pinning 归零——常态化 JFR 归票 16，本票只做一次性验证。

注意：「零事件 ≠ 零 pin」的 caveat 针对未改造点（补偿型不产生事件），不豁免本票对**这一处**的归零检查。JDK 24 / JEP 491 后此项可回收（记 spec 裁决表，不在本票处理）。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] `ensureBucket()` 由 `synchronized` 换 `ReentrantLock` + 双检，初始化语义不变（幂等、并发首建只执行一次）
- [x] 一次性 JFR（threshold=0ms）跑过，`ensureBucket` 路径零 pinning 事件
- [x] 既有 MinIO 相关测试全绿

## Answer

**落地内容**：`FileStoragePort.ensureBucket()` 由 `private synchronized` 换 `ReentrantLock` + 双检——新增 `private final ReentrantLock bucketLock`，锁外快路径 `if (bucketReady) return;` → `bucketLock.lock()` → 锁内复查 → `finally unlock`，网络 I/O（`bucketExists` / `makeBucket` / `setBucketPolicy`）全在锁内、与改前一致。字段与形状逐字照票 03 §4.3 的最小改法；`bucketReady` 仍是 `volatile`（锁外快路径的可见性靠它）。`initBucket()` / `store()` 未动；策略 JSON 文本块仅随缩进右移，渲染结果字节一致。唯一新增 import：`java.util.concurrent.locks.ReentrantLock`。**顺带收益**：改前每次 `store()` 都要进监视器，现在快路径真正无锁。

**一次性 JFR 验证（threshold=0ms）**。口径与「用 measure/ 现有资产」的偏差先说清：`ensureBucket` 的锁 + I/O 路径在生产里**只有启动时 MinIO 不可用、之后恢复**才走到（`@PostConstruct` 在主线程预热，主线程不是 VT、本就不产生 pinning 事件）——所以把这条路径放到真实应用里跑是打不到冷路径的。改用**同一份 harness 换 classpath 跑前后两臂**，直接命中改动的那个方法：`measure/verify13/harness/EnsureBucketPinProbe.java`（32 条 VT 用 `CountDownLatch` 闸门同时冲、各用全新桶、load 同一个 dev MinIO 9000），旧臂 classpath 前置 `git HEAD` 版本的 `FileStoragePort`，新臂用工作区版本；JFR 用 measure 既有的 `vt-pin-only.jfc`（threshold=0ms）。

| 臂 | 解析到的实现 | 上传结果 | `jdk.VirtualThreadPinned` | 事件栈 |
| --- | --- | --- | --- | --- |
| 旧 | git HEAD `synchronized` | 32/32 成功 | **3**（197ms / 52.6ms / 71ms） | 三条**全落在 `FileStoragePort.ensureBucket()`**，分别对应 `bucketExists` / `makeBucket` / `setBucketPolicy` |
| 新 | 工作区 `ReentrantLock` | 32/32 成功 | **0** | — |

即该点 pinning 归零；**且「零事件」不是探针失灵——同一探针在旧代码上精确命中了这个方法**（这正是 spec §5.2 那条 caveat 要排除的情形）。两臂 32/32 成功同时说明双检下并发首建只执行一次（若 `makeBucket` 被并发重入，S3 会返回 `BucketAlreadyOwnedByYou` → 业务异常）。产物 `measure/verify13/`（harness 源码 + `old.jfr` / `new.jfr`，体量小但可重生成，未入库）；验证用的两个临时桶已删。

**验证**：`FileUploadIntegrationTest` 9/9 绿；单体全量 `mvn -o test` **353 用例、0 失败**（13:28，与票 12 的 352 差 1 属该次计数口径，未动测试文件）。`grep synchronized` 复核：**全仓 `src/main` 现已零 `synchronized`**（唯一命中是本方法 Javadoc 里的说明文字），票 03 §4.3 那条「主源码唯一一处」的事实随之失效。

**code-review 两轴均无真货**：Standards 轴核 CLAUDE.md（backend §8 命名/分层、模块依赖方向）全项合规，唯一基线候选「双检的守卫重复两遍」被 spec §2.4 明文要求压制；Spec 轴逐条核过双检形状、`volatile`、初始化语义、无多余改动，通过。

**已知波及 / 范围外**：① 生产下这是 spec §2.4 定位的「**廉价保险而非热点**」，冷路径只在启动时 MinIO 不可用才被走到——本票不因此改预热策略；② JDK 24 / JEP 491 后此改造可回收（spec 裁决表已记，不在本票）；③ **常态化 JFR 与轻量验收归票 16**，本票只做一次性验证。
