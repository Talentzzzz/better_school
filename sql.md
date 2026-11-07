# 全库建表语句（SQL DDL 汇总）

> 收录 2 个工程的建表语句：better-school 单体（库 better_school，超时中心已内嵌在此）、marketing 营销（库 bs_marketing；points / raffle / drawcount / config / inventory 同属 `backend/marketing/` 一个工程）。XXL-Job 调度库（原 backend/sql/xxl-job.sql 的 8 张表）不在本文档范围内。
> **超时中心内嵌化（2026-10-16 起，[`.scratch/timeout-inline/`](.scratch/timeout-inline/map.md)）已完成**：原独立超时工程与独立库已下线，16 张 `timeout_task_00..15` 分表在 `better_school` / `better_school_test` 里（见 §1 末），与业务表同库、同事务。全库物理表数 = **42（单体业务）+ 16（单体超时分表）+ 18（营销）= 76**。
> **业务 ID 全面 UUID 化（2026-11-02 起，[`.scratch/uuid-biz-id/`](.scratch/uuid-biz-id/spec.md)）已完成**：全部业务对象的对外唯一标识统一为 **UUID 字符串**（DB 列 `VARCHAR(36)`，由应用层统一 `IIdGenerator` 在 insert 前发号、随行一次写入），Java 实体/PO/DTO 全链路 String；marketing 的雪花发号器退役。**存量数据不迁移——开发/测试库删库重建**，历史上按 BIGINT 体系书写的割接/回填脚本（`sql/backfill/`、`sql/cutover/` 全目录）已随收口整体退役删除。
> 不含种子数据 `INSERT`（schema 内置的敏感词/单行配置种子除外）；开发库与测试库共用同一份 DDL，建库语句在各自的 `init-dev.sql` / `init-test.sql`。
> 命名口径：每张表固定以 `create_time DATETIME NOT NULL COMMENT '创建时间'` 与 `update_time DATETIME NOT NULL COMMENT '更新时间'` 收尾（不带默认值，由应用写入），全库不再出现 `created_at` / `updated_at`。例外共两处：**① §1 的 `timeout_task_00..15`**（`created_at` / `updated_at`）：那是分批表从原独立超时库迁入时的既有形态、且已进 16 张分表与 mapper XML，本口径不回溯改它（见 §1 说明）；**② §1 的各域 feed 索引表与浏览明细表**（feed-index-uv 票 01 的 `post_feed_index` / `user_view_history`，market-feed-uv 票 01 的 `product_feed_index` / `errand_feed_index` / `product_view_history` / `errand_view_history`）：时间列按各自需要收窄——索引表只留 `update_time`（`feed_time` 承担行诞生时刻），明细表只留 `viewed_at`（首刷建行、重复浏览全量刷新），见 §1 对应表注释。
> 主键口径：每张表第一列恒为 `id BIGINT NOT NULL AUTO_INCREMENT`——纯物理自增、**纯内部键**，无业务含义、不写注释、禁止当对外标识或游标/水位，且（uuid-biz-id 起）**永不再与业务键同值**；业务标识一律单独成列（17 张实体主表 + `content_review`/`review_task`/`content_report` 三张治理表，如 `bs_user.user_id`、`post.post_id`、`errand_order.errand_order_id`、`content_report.report_id`），并配 `UNIQUE KEY uk_<列>`；关联表、流水表、计数器、单行配置、outbox 与超时任务表不设业务列。**例外是各域 feed 索引 / 浏览明细 / 浏览计数投影 / 评论热评索引表**（feed-index-uv 票 01 三张：`post_feed_index` / `user_view_history` / `post_view_count`；market-feed-uv 票 01 六张：`product_feed_index` / `errand_feed_index` / `product_view_history` / `errand_view_history` / `product_view_count` / `errand_view_count`；comment-count-narrow-table 票 01 一张：`post_comment_feed_index`）：投影/明细/加速列、不设独立业务列，主键直接用业务键（1:1 投影与计数表 ODKU upsert 直达，明细表二级索引叶子天然携带业务 ID 免回表），见 §1 对应表注释。

---

## 1. 单体 better-school（库 better_school，bs-* 七模块）

来源：`backend/better-school/sql/schema.sql` · 本文档收录 42 张业务表 + 16 张 `timeout_task` 分表。以下 6 张不在本文档范围：want_to_buy、errand_order_member、agent_review_log 已随求购/多人单下线及审核去 agent 化删除；errand_evaluation、user_daily_active、delay_outbox_message 本轮重构不收录。原第 30 张「超时注册意图表」已随超时内嵌化票 03 删除——注册改为业务事务内直插 `timeout_task` 分表，见下节

社区（帖子/评论/二手）、用户、钱包、chat（会话/消息/时间线，仅 1v1）、通知、审核（人工 + 举报）、跑腿与二手托管交易、信用分、敏感词、outbox。

### bs_user

```sql
CREATE TABLE IF NOT EXISTS bs_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       VARCHAR(36)  NOT NULL COMMENT '业务 ID（对外唯一标识）',
    phone         VARCHAR(20)  NOT NULL COMMENT '手机号，登录凭证',
    nickname      VARCHAR(50)  NOT NULL COMMENT '昵称',
    avatar        VARCHAR(255) NULL COMMENT '头像 URL（MinIO）',
    signature     VARCHAR(60)  NULL COMMENT '个性签名（可空，≤40 字，空即不展示）',
    gender        VARCHAR(10)  NULL COMMENT '性别 MALE/FEMALE，NULL=不展示',
    show_liked    TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '隐私开关：公开我的"赞过"（1 公开 / 0 仅自己，仅他视生效）',
    show_comments TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '隐私开关：公开我的"评论"（1 公开 / 0 仅自己，仅他视生效）',
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色 USER/AUDITOR/ADMIN',
    state         VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT '状态 NORMAL/BANNED',
    credit_score  INT          NOT NULL DEFAULT 100 COMMENT '信用分',
    last_login_at DATETIME     NULL COMMENT '最近登录时间',
    create_time   DATETIME     NOT NULL COMMENT '创建时间',
    update_time   DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_id (user_id),
    UNIQUE KEY uk_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';
```

说明：`user_id` 由注册事务内应用层 `IIdGenerator` 发号（uuid-biz-id 票 02 起），与物理 `id` 永不同值。

### post

```sql
CREATE TABLE IF NOT EXISTS post (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    post_id       VARCHAR(36)   NOT NULL COMMENT '业务 ID（对外唯一标识，UUID 字符串；物理自增 id 只作内部键）',
    user_id       VARCHAR(36)   NOT NULL COMMENT '发布者用户 ID',
    title         VARCHAR(100)  NULL COMMENT '标题（可选）',
    content       TEXT          NULL COMMENT '正文（草稿可空，提交时校验必填）',
    images        VARCHAR(1000) NULL COMMENT '图片 URL 列表（JSON 数组，MinIO 后置）',
    cover         VARCHAR(500)  NULL COMMENT '独立封面 URL（可空，可传相册外的独立上传图；展示口径：独立封面 ?? 相册第一张）',
    state         VARCHAR(20)   NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT '状态 NORMAL/PENDING_REVIEW/REJECTED/DELETED/DRAFT（先审后发：发布落库即待审，人工通过转 NORMAL，拒绝 REJECTED，撤回/被拒/未发布进 DRAFT；待审 24h 超时提醒由本表 review_entered_at 驱动轮询扫描）',
    review_round  INT           NOT NULL DEFAULT 1 COMMENT '审核轮次：累计进入待审次数（直接发布=1，草稿创建=0、提交进待审时 +1；content_review.review_round 取此列）',
    review_entered_at DATETIME  NULL COMMENT '最近一次进入待审时间（24h 待审超时提醒扫描用；裁决/撤回不清空，下次进入待审由提交语句刷新）',
    expire_at     DATETIME      NULL COMMENT '过期归档时间（满 30 天下架，XXL-Job 后置）',
    create_time   DATETIME      NOT NULL COMMENT '创建时间',
    update_time   DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_post_id (post_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '帖子表';
```

> post.category / idx_category_created 已删（feed-index-uv 票 07 收口：帖子分类退役，墙内容发现 = 时间线 + 搜索；商品的 category 是市集真需求，不受影响）；post.view_count 已删（同票：浏览切 UV 口径，真相源 user_view_history、加速列 post_view_count）。存量库不迁移（uuid-biz-id：删库重建），历史删列/割接脚本已随 uuid-biz-id 票 08 退役。

### post_comment

```sql
CREATE TABLE IF NOT EXISTS post_comment (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    comment_id  VARCHAR(36)  NOT NULL COMMENT '业务 ID（对外唯一标识，UUID 字符串）',
    post_id     VARCHAR(36)  NOT NULL COMMENT '帖子业务 ID',
    user_id     VARCHAR(36)  NOT NULL COMMENT '评论者用户 ID',
    content     VARCHAR(500)  NULL COMMENT '评论文字（可空：纯图评论时为空，与 images 至少有一个非空）',
    images      VARCHAR(1000) NULL COMMENT '图片 URL 列表（JSON 数组，最多 6 张，MinIO）',
    state       VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT '状态 NORMAL/PENDING_REVIEW/REJECTED/DELETED（先审后发，存量默认 NORMAL；列表只查 NORMAL）',
    root_id     VARCHAR(36)  NOT NULL DEFAULT '0' COMMENT '所在楼根评论业务 ID（''0''=根评论；回复根评论时=根评论 id；回复子回复时=楼根 id）',
    parent_id   VARCHAR(36)  NOT NULL DEFAULT '0' COMMENT '被回复评论业务 ID（根评论为 ''0''；回复根评论时=根评论 id；回复子回复时=该子回复 id）',
    reply_count INT          NOT NULL DEFAULT 0 COMMENT '楼内可见子回复数（仅根评论有意义，仅 NORMAL 计入，同事务维护）',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_comment_id (comment_id),
    KEY idx_post (post_id),
    KEY idx_post_state (post_id, state),
    KEY idx_root (root_id, state),
    KEY idx_user_state_created (user_id, state, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '帖子评论表';
```

> post_comment.like_count 已删（comment-count-narrow-table 票 04 收口：评论点赞计数切窄表，真源 post_comment_like_count、排序快照 post_comment_feed_index）。删列前旧列已在票 02 停写停读；存量点赞不搬（用户确认接受：历史评论点赞显示 0），删列即丢弃。存量库不迁移（uuid-biz-id：删库重建），历史割接脚本已随 uuid-biz-id 票 08 退役。
> root_id / parent_id 为 VARCHAR(36) 后，`'0'` 哨兵是字符串：SQL 里比较必须带引号（裸 `= 0` 会把 UUID 隐式转数、全部判 0）。

### product

```sql
CREATE TABLE IF NOT EXISTS product (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    product_id     VARCHAR(36)    NOT NULL COMMENT '业务 ID（对外唯一标识，UUID 字符串）',
    user_id        VARCHAR(36)    NOT NULL COMMENT '卖家用户 ID',
    title          VARCHAR(30)    NULL COMMENT '标题（草稿允许为空，提交时必填）',
    description    VARCHAR(500)   NULL COMMENT '描述',
    images         VARCHAR(1000)  NULL COMMENT '图片 URL 列表（JSON 数组，MinIO 后置）',
    price          DECIMAL(10,2)  NULL COMMENT '价格（草稿允许为空，提交时必填）',
    trade_location VARCHAR(100)   NULL COMMENT '交易地点（校区+场所）（草稿允许为空，提交时必填）',
    category       VARCHAR(20)    NULL COMMENT '分类 DIGITAL/BOOKS/DAILY/SPORTS/TICKETS/OTHER（草稿允许为空，提交时必填）',
    state          VARCHAR(20)    NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT '状态 DRAFT(草稿)/PENDING_REVIEW(待审)/ON_SALE/LOCKED(交易中)/SOLD/REJECTED(审核未通过)/DELETED（先审后售：提交进待审，审核通过转在售，拒绝转 REJECTED）',
    review_round   INT            NOT NULL DEFAULT 0 COMMENT '审核轮次：累计进入待审次数（直接发布=1，草稿提交/重新上架/在售改内容回审再 +1；content_review.review_round 取此列）',
    review_entered_at DATETIME    NULL COMMENT '最近一次进入待审时间（24h 待审超时提醒扫描用）',
    expire_at      DATETIME       NULL COMMENT '到期下架时间（进入待审时刷新满 30 天，XXL-Job 后置汇入草稿箱）',
    create_time    DATETIME       NOT NULL COMMENT '创建时间',
    update_time    DATETIME       NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_id (product_id),
    KEY idx_category_created (category, create_time),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '二手商品表';
```

> product.view_count 已删（market-feed-uv 票 06 收口：浏览切 UV 口径，真相源 product_view_history、加速列 product_view_count）。存量库不迁移（uuid-biz-id：删库重建），历史删列脚本已退役。

### product_favorite

```sql
CREATE TABLE IF NOT EXISTS product_favorite (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    user_id    VARCHAR(36)   NOT NULL COMMENT '收藏用户 ID',
    product_id VARCHAR(36)   NOT NULL COMMENT '商品业务 ID',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_product (user_id, product_id),
    KEY idx_product (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品收藏表';
```

### post_like

```sql
CREATE TABLE IF NOT EXISTS post_like (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    post_id    VARCHAR(36)   NOT NULL COMMENT '帖子业务 ID',
    user_id    VARCHAR(36)   NOT NULL COMMENT '点赞用户 ID',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_post_user (post_id, user_id),
    KEY idx_user (user_id) COMMENT '「我的赞过」反查（show_liked 隐私开关他视查询）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '帖子点赞关系表';
```

### post_like_count

```sql
CREATE TABLE IF NOT EXISTS post_like_count (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    post_id     VARCHAR(36)   NOT NULL COMMENT '帖子业务 ID（唯一）',
    like_count  INT      NOT NULL DEFAULT 0 COMMENT '点赞数（MQ 异步合并刷盘落此表；点赞/取消的明细关系在 post_like）',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_post (post_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '帖子点赞计数表（专用窄行，不与 post 内容/状态写入抢锁）';
```

说明（计数决策：MySQL + MQ，全站计数不走 Redis）：点赞事务 = `INSERT post_like`（uk 幂等）+ 投递计数消息（至少一次）→ 消费端按帖子聚合窗口内的 ±1 增量，合并成一条 `UPDATE` 落 `post_like_count`。展示值短暂滞后于刷盘窗口（最终一致）；取消点赞发负增量。放弃 Redis 的原因：`post_like` 关系行本来就是事实源，Redis 只是一层易失缓冲，去掉后少一套对账/重建，DB 即唯一事实源。（帖子点赞现已走进程内合并器直写，见 docs/db/community.md；浏览段已被 feed-index-uv 票 02 的 UV 口径取代：明细 `user_view_history` 即事实源，`post.view_count` 列随票 07 删除——见 post_view_count 一节。）

**消息级幂等键是生产端生成的 `msgId`（随消息载荷走），不是关系表的 uk**：`post_like` 的 uk `(post_id, user_id)` 只防「重复点赞同一帖」这一关系行重放，不能当消息幂等键——`like → unlike → like` 是合法序列，若按关系键去重会把第三次点赞误判为重投而丢增量。故 outbox 载荷里带 `msgId`（UUID），消费端按它做环形去重吸收 broker 重投（见 `PostCountMessage`）；outbox 重发复用同一 payload，故同一条消息重发仍是同 `msgId`，天然被吸收。

### post_comment_count

```sql
CREATE TABLE IF NOT EXISTS post_comment_count (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    post_id       VARCHAR(36)   NOT NULL COMMENT '帖子业务 ID（唯一）',
    comment_count INT      NOT NULL DEFAULT 0 COMMENT '评论数（评论落库同事务 +1、删评 -1，只计 NORMAL 态）',
    create_time   DATETIME NOT NULL COMMENT '创建时间',
    update_time   DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_post (post_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '帖子评论计数表（专用窄行，评论同步 +1 不再锁 post 行）';
```

### post_comment_like

```sql
CREATE TABLE IF NOT EXISTS post_comment_like (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    comment_id VARCHAR(36)   NOT NULL COMMENT '评论业务 ID（根评论或子回复）',
    user_id    VARCHAR(36)   NOT NULL COMMENT '点赞用户 ID',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_comment_user (comment_id, user_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评论点赞表';
```

### post_comment_like_count（comment-count-narrow-table 票 02）

```sql
CREATE TABLE IF NOT EXISTS post_comment_like_count (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    comment_id  VARCHAR(36)   NOT NULL COMMENT '评论业务 ID（唯一；根评论与子回复都入表）',
    like_count  INT      NOT NULL DEFAULT 0 COMMENT '评论点赞数（点赞合并事务/取消赞同事务直写；GREATEST(0,±N) clamp；真源 = post_comment_like 条数）',
    create_time DATETIME NOT NULL COMMENT '创建时间（= 审核通过时刻）',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_comment (comment_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评论点赞计数表（专用窄行，不与评论内容/状态写入抢锁；行诞生于审核通过事务）';
```

说明：评论点赞的**计数真源**（原 `post_comment.like_count` 主列，票 04 已删）。全量评论都入表、根评论与子回复同形，行诞生于**审核通过事务**（`INSERT IGNORE`，affected = 0 落 warn）——不变式是「行诞生于审核通过」而非「行诞生于第一次点赞」，所以点赞/取消赞的 `UPDATE ... SET like_count = GREATEST(0, like_count + delta)` 命中 0 行只落 warn 交手工对账，**不 ODKU 补行**；计数写不刷 `post_comment` 主行 `update_time`（主行没有排序键依赖它）。**无二级索引**：取数全走 `comment_id` 唯一键点查，热评排序走 `post_comment_feed_index`（本表 like_count 索引给不出「单帖内按赞数排序」要的序）。拆窄表的动机是形状统一（不是性能改造）：照帖子侧「计数真源窄表 + 排序投影索引」两件套同构。存量点赞不回填（删库重建，无历史行）。

### post_comment_feed_index（comment-count-narrow-table 票 01）

```sql
CREATE TABLE IF NOT EXISTS post_comment_feed_index (
    comment_id  VARCHAR(36)   NOT NULL COMMENT '根评论业务 ID（唯一；子回复不入索引）',
    post_id     VARCHAR(36)   NOT NULL COMMENT '所属帖子业务 ID（「最热」按此前缀把扫描锁在单帖）',
    like_count  INT      NOT NULL DEFAULT 0 COMMENT '点赞数快照（点赞/取消赞同事务旁带 ±N，真源 post_comment_like_count——票 02 前为主列 post_comment.like_count；UPDATE 落 0 行落日志交重建 job；「最热」排序键）',
    create_time DATETIME NOT NULL COMMENT '创建时间（= 审核通过时刻）：点赞数同值时按它升序收敛成确定序（发布先后；UUID 无序，不能再拿业务 ID 当时间代理）',
    update_time DATETIME NOT NULL COMMENT '更新时间（技术时间戳，禁止当排序键）',
    PRIMARY KEY (comment_id),
    KEY idx_post_like (post_id, like_count, create_time, comment_id) COMMENT '「最热」：单帖前缀扫描收窄范围（覆盖索引不回表）+ 帖内按 like_count DESC, create_time ASC, comment_id ASC 排序取页'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '根评论热评排序投影（post_comment 的可见根行 1:1 索引，同事务直写）';
```

说明：根评论「最热」档的**排序投影**，照帖子 `post_feed_index` 形态只做「单帖内按赞数取页」一件事；**不是真值源**（展示的 `likeCount` 仍取 `post_comment_like_count`）。行集 = 当前可见根评论：审核通过事务 `INSERT`、根评论软删事务物理 `DELETE`——评论软删是终态、无恢复路径，因此不像帖子那样「行常驻 + `state` 快照镜像」（该列在这里恒为 NORMAL）；子回复不入本表（楼内不按赞排，只展示赞数）。读法：`post_id` 前缀先把扫描锁在单帖（覆盖索引不回表），再在帖内按 `(like_count DESC, create_time ASC, comment_id ASC)` 取页——uuid-biz-id 票 03 起同值收敛键从「业务 id 升序」改为「发布时刻 + 业务 id」复合键（UUID 无序，业务 id 不再是时间代理），索引随之扩为四列；方向混合索引序给不全，排序落在「本帖那几十行」的窄范围内；offset 分页 + total 对外契约不变。漂移（点赞旁带命中 0 行的 warn、索引行被绕过直改、软删漏删）由全量重建 job `commentFeedIndexRebuildJob`（XXL-Job 手动触发）单事务「`DELETE` 清空 → `INSERT ... SELECT` 重灌」兜底，重灌行集 = `post_comment` 的 NORMAL 根评论、赞数取计数窄表（无行按 0）；它**只兜索引不兜窄表**，窄表缺行只能手工补行后重跑重建（点赞不会自愈）。

### post_feed_index（feed-index-uv 票 01）

```sql
CREATE TABLE IF NOT EXISTS post_feed_index (
    post_id     VARCHAR(36) NOT NULL COMMENT '帖子业务 ID（post.post_id，1:1 投影）',
    state       VARCHAR(20) NOT NULL DEFAULT 'NORMAL' COMMENT '帖子状态快照（与 post.state 同域，状态流转事务同步；feed 只查 NORMAL）',
    feed_time   DATETIME    NOT NULL COMMENT '进入当前对外可见轮的通过时刻（approve 事务写入：行诞生落第一次、重提再通过刷新；其余写路径不碰；「最新」排序键）',
    like_count  INT         NOT NULL DEFAULT 0 COMMENT '点赞数快照（赞合并事务旁路 ±N；真源 post_like_count，UPDATE 落 0 行落日志交重建 job；「热榜」排序键）',
    update_time DATETIME    NOT NULL COMMENT '更新时间（技术时间戳，禁止当排序键）',
    PRIMARY KEY (post_id),
    KEY idx_state_feed (state, feed_time, post_id) COMMENT '「最新」：state 过滤 + feed_time 游标分页',
    KEY idx_state_like (state, like_count, feed_time, post_id) COMMENT '「热榜」：state 过滤 + like_count 游标分页；点赞数同值时按 feed_time DESC 收敛成确定序（UUID 无序，不能再拿 post_id 当时间代理）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '帖子 feed 异构索引（post 的 1:1 排序投影，同事务直写）';
```

说明：主页 feed 排序查询从 `post` 直查切换的落点（MySQL 异构索引，不用 ES——feed 只需要排序+过滤，索引表与 post 同库可同事务直写；换 ES 的触发条件是个性化推荐/多路召回，届时只换索引层实现、读路径不动）。一帖一行 1:1 投影；排序键永远不用 `update_time`（技术时间戳，会被浏览回写等无关写污染）；游标分页弃 offset。「最新」档 `WHERE state = ? AND (feed_time, post_id) < (?, ?)` 两段谓词；「热榜」档同值收敛键是 `feed_time DESC, post_id DESC`（uuid-biz-id 票 03：UUID 无序化后确定序由时间键兜底），谓词相应三段展开。热榜 = 纯 `like_count`（去掉 `like_count + comment_count*2` 权重公式；评论数不参与 feed 排序）。全量重建 job `feedIndexRebuildJob`（feed-index-uv 票 07，xxl-job 手动触发）从 post + 窄表 + 审核记录清空重灌、顺带把 `post_view_count` 校准成明细行数，是同事务直写丢更新/漂移的兜底恢复手段。

### user_view_history（feed-index-uv 票 01）

```sql
CREATE TABLE IF NOT EXISTS user_view_history (
    user_id   VARCHAR(36)   NOT NULL COMMENT '浏览者用户 ID',
    post_id   VARCHAR(36)   NOT NULL COMMENT '帖子业务 ID',
    viewed_at DATETIME NOT NULL COMMENT '最近浏览时刻（合并事务内 INSERT IGNORE 后对全组键 UPDATE 刷新；浏览历史排序键）',
    PRIMARY KEY (user_id, post_id),
    KEY idx_user_viewed (user_id, viewed_at) COMMENT '浏览历史：user_id 过滤 + viewed_at 倒序游标分页'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户浏览历史（UV 明细即事实源，post_view_count 是它的加速列）';
```

说明：浏览体系切 UV 口径的事实源（一人一帖一行）：`INSERT IGNORE` 成功（affected = 1）才算一次新浏览，重复进详情只刷 `viewed_at` 不加数；同时是浏览历史与 feed 已读灰显的数据源，用户维度，将来按 user_id 分片友好。落库形态 = `likePosts` 的镜像：合并器一组一个事务，`INSERT IGNORE` 明细 → `UPDATE viewed_at`（全量刷新，无害）→ 计数 += 新增数；不用 upsert 计数（affected rows 会被同秒重复刷 viewed_at 打乱）。时间列刻意收窄到 `viewed_at` 一列（首刷建行、重复浏览全量刷新，不设 create_time/update_time——命名口径在这张表上的显式例外）。

### post_view_count（feed-index-uv 票 01）

```sql
CREATE TABLE IF NOT EXISTS post_view_count (
    post_id     VARCHAR(36)   NOT NULL COMMENT '帖子业务 ID（唯一）',
    view_count  INT      NOT NULL DEFAULT 0 COMMENT '浏览 UV 数（= user_view_history 行数；详情读路径 selectCountsByPostId 的 view 段换源到本表）',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (post_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '帖子浏览计数表（UV 加速列，事实源在 user_view_history）';
```

说明：浏览 UV 加速列，精确值 = `user_view_history` 行数，本表不是真相源。与点赞计数的不对称：浏览没有取消语义，计数只增不减，合并事务按 `INSERT IGNORE` 受影响行数累加。`post.view_count` 列已随之退役删除（feed-index-uv 票 07 收口；存量库不迁移，历史删列脚本已随 uuid-biz-id 票 08 退役）；加速列漂移由重建 job 按明细行数校准。

### product_feed_index（market-feed-uv 票 01）

```sql
CREATE TABLE IF NOT EXISTS product_feed_index (
    product_id  VARCHAR(36)   NOT NULL COMMENT '商品业务 ID（product.product_id，1:1 投影）',
    state       VARCHAR(20)   NOT NULL DEFAULT 'ON_SALE' COMMENT '商品状态快照（与 product.state 同域，状态流转事务同步；feed 只查 ON_SALE）',
    feed_time   DATETIME      NOT NULL COMMENT '进入当前对外可见轮的通过时刻（approve 事务写入：行诞生落第一次、重提再通过刷新；其余写路径不碰；「最新」排序键）',
    create_time DATETIME      NOT NULL COMMENT '商品发布时刻快照（product.create_time，approve 事务随行写入；「热度」同值收敛键：view_count 同值时按 create_time DESC 收敛成确定序——UUID 无序，不能再拿 product_id 当时间代理）',
    price       DECIMAL(10,2) NOT NULL COMMENT '价格快照（product.price；在售改价事务同事务旁路同步，否则价格排序漂移；「价格升降」排序键）',
    view_count  INT           NOT NULL DEFAULT 0 COMMENT '浏览 UV 数快照（浏览合并事务旁路 ±N；真源 product_view_count，UPDATE 落 0 行落日志交重建 job；「热度」排序键）',
    update_time DATETIME      NOT NULL COMMENT '更新时间（技术时间戳，禁止当排序键）',
    PRIMARY KEY (product_id),
    KEY idx_state_feed (state, feed_time, product_id) COMMENT '「最新」：state 过滤 + feed_time 游标分页（时间升序反向扫描）',
    KEY idx_state_view (state, view_count, create_time, product_id) COMMENT '「热度」：state 过滤 + view_count DESC, create_time DESC, product_id DESC 游标分页',
    KEY idx_state_price (state, price, product_id) COMMENT '「价格升降」：state 过滤 + price 游标分页'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品 feed 异构索引（product 的 1:1 排序投影，同事务直写）';
```

说明：市集闲置 tab 推荐读路径的落点，照 `post_feed_index` 先例分域落表（不与 post 泛化合表，post 侧一行不动）。一商品一行 1:1 投影，feed 只取 `state = 'ON_SALE'`（大厅闲置 tab 直查同口径）；排序键 = 热度（`view_count` 降，浏览量显示为热度）/ 时间降 / 时间升（同索引反向扫描，不单设索引）/ 价格升 / 价格降。uuid-biz-id 票 05 起「热度」档是**三段收敛键** `view_count DESC, create_time DESC, product_id DESC`：新增 `create_time` 列为主表发布时刻快照（approve 事务随行写入，重建投影同口径回灌），同值排序在 UUID 无序化后仍确定，游标谓词与 VO 从四段扩为五段。`price` 进索引表是帖子没有的新排序键——商品在售改价立即生效不进审核，改价事务必须同事务旁路同步（market-feed-uv 票 03）。索引行与计数窄表的漂移（浏览旁路命中 0 行的 warn）由 `productFeedIndexRebuildJob` 手动重建兜底（票 06：清空重灌 + 计数校准，一个事务）。

### errand_feed_index（market-feed-uv 票 01）

```sql
CREATE TABLE IF NOT EXISTS errand_feed_index (
    errand_order_id VARCHAR(36) NOT NULL COMMENT '跑腿订单业务 ID（errand_order.errand_order_id，1:1 投影）',
    state           VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED' COMMENT '跑腿状态快照（与 errand_order.state 同域，13 态流转点同步；feed 只查 PUBLISHED，行常驻其余状态不可见）',
    feed_time       DATETIME    NOT NULL COMMENT '进入大厅的时刻（审核通过事务写入：行诞生落第一次；跑腿无重提再过审，其余写路径不碰；「最新」排序键）',
    create_time     DATETIME    NOT NULL COMMENT '订单创建时刻快照（errand_order.create_time，approve 事务随行写入；「热度」同值收敛键：view_count 同值时按 create_time DESC 收敛成确定序——UUID 无序，不能再拿 errand_order_id 当时间代理）',
    price           DECIMAL(10,2) NOT NULL COMMENT '悬赏快照（errand_order.reward；行只在审核通过时诞生、彼时悬赏必已支付冻结，故非空；「价格升降」排序键）',
    view_count      INT         NOT NULL DEFAULT 0 COMMENT '浏览 UV 数快照（浏览合并事务旁路 ±N；真源 errand_view_count，UPDATE 落 0 行落日志交重建 job；「热度」排序键）',
    update_time     DATETIME    NOT NULL COMMENT '更新时间（技术时间戳，禁止当排序键）',
    PRIMARY KEY (errand_order_id),
    KEY idx_state_feed (state, feed_time, errand_order_id) COMMENT '「最新」：state 过滤 + feed_time 游标分页（时间升序反向扫描）',
    KEY idx_state_view (state, view_count, create_time, errand_order_id) COMMENT '「热度」：state 过滤 + view_count DESC, create_time DESC, errand_order_id DESC 游标分页',
    KEY idx_state_price (state, price, errand_order_id) COMMENT '「价格升降」：state 过滤 + price 游标分页'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '跑腿 feed 异构索引（errand_order 的 1:1 排序投影，同事务直写）';
```

说明：跑腿大厅推荐读路径的落点。行常驻 + state 快照（13 态流转频繁，照帖子做法：每个影响 state 的流转点同事务同步快照，读路径 `WHERE state = 'PUBLISHED'` 挑可见行）——大厅前端恒传 `status=PUBLISHED`（`front/src/views/MarketView.vue`），这是 feed 读路径的统一公开口径。跑腿无存量浏览量，`view_count` 从 0 起步；「热度」档与商品侧同口径的三段收敛键（uuid-biz-id 票 05，`create_time` 快照列同批新增）；漂移由 `errandFeedIndexRebuildJob` 手动重建兜底（票 06）。

### product_view_history（market-feed-uv 票 01）

```sql
CREATE TABLE IF NOT EXISTS product_view_history (
    user_id    VARCHAR(36)   NOT NULL COMMENT '浏览者用户 ID',
    product_id VARCHAR(36)   NOT NULL COMMENT '商品业务 ID',
    viewed_at  DATETIME NOT NULL COMMENT '最近浏览时刻（合并事务内 INSERT IGNORE 后对全组键 UPDATE 刷新）',
    PRIMARY KEY (user_id, product_id),
    KEY idx_user_viewed (user_id, viewed_at) COMMENT '按人浏览明细倒序（市集页浏览痕迹/灰显）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品浏览历史（UV 明细即事实源，product_view_count 是它的加速列）';
```

### errand_view_history（market-feed-uv 票 01）

```sql
CREATE TABLE IF NOT EXISTS errand_view_history (
    user_id        VARCHAR(36)   NOT NULL COMMENT '浏览者用户 ID',
    errand_order_id VARCHAR(36) NOT NULL COMMENT '跑腿订单业务 ID',
    viewed_at      DATETIME NOT NULL COMMENT '最近浏览时刻（合并事务内 INSERT IGNORE 后对全组键 UPDATE 刷新）',
    PRIMARY KEY (user_id, errand_order_id),
    KEY idx_user_viewed (user_id, viewed_at) COMMENT '按人浏览明细倒序（市集页浏览痕迹/灰显）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '跑腿浏览历史（UV 明细即事实源，errand_view_count 是它的加速列）';
```

说明（两域明细表合并）：照 `user_view_history` 先例分域落表——一人一对象一行 = UV 事实源，`INSERT IGNORE` 成功才算一次新浏览，重复进详情只刷 `viewed_at`；同时是市集页已读灰显的数据源。与 post 侧的语义变更：**匿名不计入**（商品旧口径匿名也计、纯累加 PV 近似值，随市集 UV 体系上线作废）。无历史明细可搬（删库重建），UV 从建库起精确累计。`/me/history` 页保持帖子专属，市集浏览记录只体现在市集页（map 拍板）。

### product_view_count（market-feed-uv 票 01）

```sql
CREATE TABLE IF NOT EXISTS product_view_count (
    product_id  VARCHAR(36)   NOT NULL COMMENT '商品业务 ID（唯一）',
    view_count  INT      NOT NULL DEFAULT 0 COMMENT '浏览 UV 数（= product_view_history 行数；商品详情读路径 view 段换源到本表）',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品浏览计数表（UV 加速列，事实源在 product_view_history）';
```

### errand_view_count（market-feed-uv 票 01）

```sql
CREATE TABLE IF NOT EXISTS errand_view_count (
    errand_order_id VARCHAR(36) NOT NULL COMMENT '跑腿订单业务 ID（唯一）',
    view_count      INT      NOT NULL DEFAULT 0 COMMENT '浏览 UV 数（= errand_view_history 行数；跑腿详情读路径 view 段接线到本表）',
    create_time     DATETIME NOT NULL COMMENT '创建时间',
    update_time     DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (errand_order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '跑腿浏览计数表（UV 加速列，事实源在 errand_view_history）';
```

说明（两域计数表合并）：照 `post_view_count` 先例的 UV 加速列，精确值 = 各域明细表行数，不是真相源。商品存量 PV 已随旧口径废弃（PV 无法换算 UV，`product.view_count` 旧列票 06 删列；存量库不迁移，历史清零/删列脚本已随 uuid-biz-id 票 08 退役），两表从空表（= 0）起步。两域加速列的漂移（窄表被绕过直改、缺行、孤儿行）由各域重建 job 校准回明细行数。

### outbox_message

```sql
CREATE TABLE IF NOT EXISTS outbox_message (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    msg_id      VARCHAR(64) NOT NULL COMMENT '消息ID（幂等/去重）',
    destination VARCHAR(64) NOT NULL COMMENT '消息目的地（destination 语法：topic 或 topic:tag，由业务端自行解析）',
    payload     TEXT        NOT NULL COMMENT '消息内容 JSON',
    state       TINYINT     NOT NULL DEFAULT 0 COMMENT '0待发送 1已发送 2发送失败(重试耗尽)',
    retry_count INT         NOT NULL DEFAULT 0 COMMENT '已重试次数',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_id (msg_id),
    KEY idx_state (state)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '本地消息表(outbox)';
```

### timeout_task_00 .. timeout_task_15（超时中心内嵌化票 02）

超时任务分表（16 张同构，业务/测试 SQL 一律写逻辑表 `timeout_task`，物理路由由 ShardingSphere 的 hint 强制路由决定：`bs-app/src/main/resources/sharding.yaml` + `bs-infrastructure/.../sharding/TimeoutTaskShardSlots`）。原来在独立库，内嵌化后与业务表同库——超时行与业务写入同一条连接、同一个本地事务。回调路由列已随进程内分发删除（原独立中心的 Dubbo 回调路由列）。时间列保留 `created_at`/`updated_at`（全库 `create_time`/`update_time` 口径的唯一例外：按原样自原独立超时库迁入，改名要动 16 份 DDL + mapper XML + 存量迁移，收益只有命名整齐；见文首命名口径）。

槽位 = `floorMod(hash(biz_id), 16)`（**hash 输入只有 `biz_id`**：同一业务对象的多种超时/多轮次落同一张表；`uk_type_biz (task_type, biz_id)` 的全局幂等不受影响——同键行永远同表；biz_id 是 VARCHAR(64) 字符串，uuid-biz-id 后承载 UUID 复合编码，hash 走字符串）。槽位 g 是「库 × 表」两维表达的全局展平编号：`db = g / 16`、`table = g % 16`；本期 `dbCount = 1` 且**不得启用 >1**——超时行落到另一个库 = 与业务写入不同事务（硬约束，见 `backend/docs/architecture/timeout.md §0`）。分表 DDL 逐张相同，`schema.sql` 里完整写出；下面列逻辑表形状（`timeout_task_00` 那份）：

```sql
CREATE TABLE IF NOT EXISTS timeout_task_00 (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键（无业务含义）',
    task_type       VARCHAR(64)  NOT NULL COMMENT '任务类型（业务对象上的一种超时动作，如 PAY_TIMEOUT）',
    biz_id          VARCHAR(64)  NOT NULL COMMENT '业务引用 id（不带快照；复合引用编码进此列；分片 hash 输入）',
    action_time     DATETIME     NOT NULL COMMENT '理论到期时刻（单轮内不可变；终态复活=重开一轮时刷新；回调时携带，供业务监控精度）',
    next_fire_time  DATETIME     NOT NULL COMMENT '下次可触发时刻（注册时=action_time；失败重试后=now+退避）',
    status          VARCHAR(16)  NOT NULL COMMENT 'WAITING/RETRYING/SUCCESS/FAILED/CANCELED（5 态，无 PROCESSING）',
    retry_count     INT          NOT NULL DEFAULT 0 COMMENT '已重试次数（16 次封顶进 FAILED）',
    created_at      DATETIME     NOT NULL COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_biz (task_type, biz_id),
    KEY idx_scan (status, next_fire_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务分表 00';
```

> ⚠️ **业务 / 运维 SQL 只写逻辑表名 `timeout_task`，不要写 `timeout_task_07` 这种物理名**：经 ShardingSphere 连接点查物理分表是 `TableNotFoundException`（物理表不在逻辑元数据里）；物理名只给分片算法、裸 MySQL 运维连接（grep / 单表 `DROP` / 巡检）用。

### room

```sql
CREATE TABLE IF NOT EXISTS room (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    room_id          VARCHAR(36)  NOT NULL COMMENT '业务 ID（对外唯一标识，UUID 字符串，应用层 insert 前由 IdGenerator 生成一次写入）',
    source           VARCHAR(20)  NULL COMMENT '会话来源（首次由市集「私信联系」入口建会话时写入、首来源钉死不覆盖；首页/主页入口建的为 NULL）：ERRAND/PRODUCT',
    initiator_id     VARCHAR(36)       NULL COMMENT '首建发起人 user_id（对家角色标签的相对视角锚点；来源为 NULL 时也为 NULL）',
    last_seq         BIGINT       NOT NULL DEFAULT 0 COMMENT '消息序号水位（取号器）：发消息事务内 UPDATE last_seq=last_seq+1 原子取号，回滚无空洞',
    state            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT '与取号器同行：一把行锁完成取号+终态判定；ACTIVE/SEALED（封盘即宿主业务终态，禁发言禁成员变更，历史只读；当前恒 1v1，SEALED 不再产生，列随口径保留）',
    create_time      DATETIME     NOT NULL COMMENT '创建时间',
    update_time      DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_room_id (room_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话表（1v1，纯取号器 + 会话真相：预览/排序已迁用户维度 user_room，未读数由 last_seq − 水位线现算）';
```

> room.user_min / room.user_max / uk_direct_pair 已迁出（配对收口：1v1 查重与配对映射独立成 user_pair_room 表，room 回归纯会话实体、不知道「配对」的存在）；room.last_msg_type / last_msg_preview / last_msg_at 已迁出（排序键迁用户维度 user_room；预览去副本化后连 user_room 也不落，读时按 message 真相现算，room 回归纯取号器 + 会话真相）。存量库不迁移（uuid-biz-id：删库重建），历史割接脚本已随 uuid-biz-id 票 08 退役。

### user_pair_room

```sql
CREATE TABLE IF NOT EXISTS user_pair_room (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    user_min    VARCHAR(36)   NOT NULL COMMENT '1v1 双方 user_id 的规范序在前方（字符串字典序，uk_pair 首列）',
    user_max    VARCHAR(36)   NOT NULL COMMENT '1v1 双方 user_id 的规范序在后方（字符串字典序）',
    room_id     VARCHAR(36)   NOT NULL COMMENT '指向 room.room_id（UUID 字符串）',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pair (user_min, user_max) COMMENT '1v1 查重：同一对人恒一行，建会话的幂等裁决者（并发建会话撞键即回读既有 room_id）',
    UNIQUE KEY uk_room_id (room_id) COMMENT '配对→会话反查锚点（一会话恒一行配对）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户对 → 1v1 会话映射（查重与反查专用，room 不再承载配对）';
```

说明：建会话事务的收尾闸门：room + 两行 user_room + 两行 room_member 名册 + 一行配对同事务；并发建同一对人时后到者撞 uk_pair 整事务回滚（room/成员行不落），服务层捕获 DuplicateKeyException 后经本表幂等回读既有 room_id。配对键沿用归一化（无序对在有序存储里的规范表示），uuid-biz-id 票 04 起取**字符串字典序**规范序（user_min = 字典序在前的一方；原数值 min/max 已失效——UUID 不可比大小），由 `DirectRoomPairVO` 收敛。会话列表的对家 user_id 由会话维度名册表 room_member 回查直出（列表四段查之③，见下）。

### user_room（chat-member-table-refactor 票 01/02）

```sql
CREATE TABLE IF NOT EXISTS user_room (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    room_id          VARCHAR(36)  NOT NULL COMMENT '会话业务键（room.room_id，UUID 字符串）',
    user_id          VARCHAR(36)       NOT NULL COMMENT '成员 user_id（用户维度行主：分库片键语义贴 user_id）',
    last_msg_time    DATETIME     NULL COMMENT '最后消息时间副本（会话列表排序键；预览不落副本，读时按 message 真相现算）',
    read_seq         BIGINT       NOT NULL DEFAULT 0 COMMENT '已读水位线：未读数 = room.last_seq − 此列（服务端多一次查询取真源现算）；打开会话即推到 room.last_seq',
    hidden_at        DATETIME     NULL COMMENT '会话隐藏时间（用户侧删除会话，软删）；新消息清空弹回',
    create_time      DATETIME     NOT NULL COMMENT '创建时间',
    update_time      DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_room (user_id, room_id) COMMENT '每用户每会话一行（片键 user_id 前置：分库日即 user 片唯一键查询面）',
    KEY idx_user_msg (user_id, last_msg_time) COMMENT '会话列表驱动索引（user_id 等值 + last_msg_time 倒序取页，分库日即 user 片单分片查询面）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户会话状态（每用户每会话一行；用户维度会话列表唯一查询面，只装 per-user 状态：排序键 last_msg_time + 水位线 + 隐藏位，预览读时现算、message 唯一真相，未读数不落库）';
```

> chat-member-table-refactor 票 01（双维度拆分）：原 room_member（用户维度成员状态行）改名 user_room（last_read_seq→read_seq、last_msg_at→last_msg_time）；会话维度名册另立新表 room_member（「这个会话有谁」，1v1 恒两行，join_seq 入群水位）。票 02（预览去副本）：user_room.last_msg_type / last_msg_preview 已迁出——预览不落副本，列表读时按 (room_id, room.last_seq) 点查 message 现算渲染，message 是预览唯一真相（撤回即时反映）。分库片键语义：user_room 贴 user_id（uk 首列前置）、room_member 名册贴 room_id（chat-shard-ready 预案对齐归票 03）；存量库不迁移（uuid-biz-id：删库重建）。

### room_member（会话成员名册，chat-member-table-refactor 票 01）

```sql
CREATE TABLE IF NOT EXISTS room_member (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    room_id     VARCHAR(36)  NOT NULL COMMENT '会话业务键（room.room_id，UUID 字符串；名册分库片键语义贴 room_id，uk 首列）',
    user_id     VARCHAR(36)  NOT NULL COMMENT '成员 user_id',
    join_seq    BIGINT       NOT NULL DEFAULT 0 COMMENT '入群水位：1v1 建会话时为 0；群聊上线即「新成员从哪条 seq 起收消息」',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_room_user (room_id, user_id) COMMENT '每会话每成员一行（1v1 恒两行；建会话事务与两行 user_room 同事务写入）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话成员名册（会话维度「这个会话有谁」的正式查询面：对家回查/成员扇出，群聊上线即扇出名册与成员展示地基）';
```

说明：名册是**会话维度**行（与用户维度的 user_room 严格分工：名册答「会话有谁」、user_room 答「我看到的状态」），分库日贴 room_id 片键；会话列表的对家 user_id、将来的成员扇出/成员展示都从它取。无业务 ID 列：对外按 (room_id, user_id) 定位。

### message

```sql
CREATE TABLE IF NOT EXISTS message (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    msg_id        VARCHAR(36)   NOT NULL COMMENT '业务 ID（对外唯一标识，UUID 字符串，应用层 insert 前由 IdGenerator 生成一次写入）：跨表引用与对外暴露用它',
    room_id       VARCHAR(36)   NOT NULL COMMENT '会话业务键（room.room_id，UUID 字符串）',
    seq           BIGINT        NOT NULL COMMENT '消息序号（room.last_seq 取号；会话内从 1 起）',
    from_user_id  VARCHAR(36)   NOT NULL COMMENT '发送者 user_id（1v1 无系统消息，恒非空）',
    msg_type      VARCHAR(20)   NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/IMAGE/CARD 三类',
    content       VARCHAR(1024) NULL COMMENT 'TEXT=裸文本（上限 500 由代码常量收敛） / IMAGE=JSON{url,width,height} / CARD=恒空',
    client_msg_id VARCHAR(36)   NULL COMMENT '客户端幂等键（UUID）：重发复用同键',
    ref_type      VARCHAR(20)   NULL COMMENT 'CARD 引用类型 PRODUCT/POST/ERRAND（仅 CARD 非空）',
    ref_id        VARCHAR(36)   NULL COMMENT 'CARD 引用对象业务 ID（POST/PRODUCT/ERRAND 均为 UUID 字符串，票 03/05；渲染时实时查，失效显示「内容已失效」）',
    state         VARCHAR(20)   NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/RECALLED：撤回占 seq 不回退、计入未读',
    recalled_at   DATETIME      NULL COMMENT '撤回时间',
    recalled_by   VARCHAR(20)   NULL COMMENT 'USER=发送者限时窗口内自撤 / AUDITOR=举报强制撤回',
    create_time   DATETIME      NOT NULL COMMENT '创建时间',
    update_time   DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_id (msg_id) COMMENT '业务键唯一；timeline 与对外接口的引用锚点',
    UNIQUE KEY uk_room_seq (room_id, seq) COMMENT '历史分页游标 + 推送判重键',
    UNIQUE KEY uk_room_client (room_id, client_msg_id) COMMENT '发送幂等：撞键回查收敛同一条'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息内容表（内容中心，只写一份）';
```

说明：uuid-biz-id 票 04 起/msg_id 由应用层发号（原「插 0 同事务回写 msg_id=id」已退役），发消息事务顺序改为「insert 前生成 msg_id → 插 message → timeline 扇出引用该 msg_id」；seq 仍是会话内单调序号（BIGINT 不变），与消息全局主键是两个空间。timeline.msg_id 外联本列（同为 UUID 字符串）；举报（content_report.target_type=MESSAGE）按 msg_id 字符串等值定位，经 timeline 路由语义不变；分库预案中 room_id/msg_id 片键哈希一律按字符串哈希（禁数值取模假设，见 chat-shard-ready spec 头注记）。

### timeline

```sql
CREATE TABLE IF NOT EXISTS timeline (
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    user_id      VARCHAR(36)   NOT NULL COMMENT '收件人 user_id',
    room_id      VARCHAR(36)   NOT NULL COMMENT '会话业务键（冗余：判重键与消息定位，免回查；UUID 字符串）',
    seq          BIGINT   NOT NULL COMMENT '消息序号副本',
    msg_id       VARCHAR(36)   NOT NULL COMMENT '消息业务 ID（外联 message.msg_id，UUID 字符串）',
    delivery_seq BIGINT   NOT NULL COMMENT 'per-user 投递序号：单调且 = 提交序，增量补拉游标（禁用 id 当游标——分配序 ≠ 提交序，并发下漏拉）',
    create_time  DATETIME NOT NULL COMMENT '创建时间',
    update_time  DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_delivery (user_id, delivery_seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '时间线（写扩散收件箱，只插不改）';
```

### user_seq

```sql
CREATE TABLE IF NOT EXISTS user_seq (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    user_id     VARCHAR(36)   NOT NULL COMMENT '用户 ID',
    seq         BIGINT   NOT NULL DEFAULT 0 COMMENT '投递序号计数器（0 基）',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user (user_id) COMMENT '计数器取号的冲突键（ODKU 靠它命中）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '投递序号计数器（每用户一行）';
```

### notification

```sql
CREATE TABLE IF NOT EXISTS notification (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    notification_id  VARCHAR(36)  NOT NULL COMMENT '业务 ID（对外唯一标识，UUID 字符串；uuid-biz-id 票 06：应用层 IIdGenerator 随 insert 一次写入，物理自增 id 保留纯内部键）',
    user_id          VARCHAR(36)  NOT NULL COMMENT '接收用户',
    scope            VARCHAR(20)  NOT NULL COMMENT '收件范围（写入时由生产点显式标注，禁止从 (type,refType) 推导——同一 ORDER_EVENT×ERRAND 发用户=MARKET_ERRAND、发审核员=BOTTOM_SYSTEM）：MARKET_ERRAND/MARKET_PRODUCT(预留,当前无生产方)/BOTTOM_SYSTEM/INTERACTION_LIKE/INTERACTION_COMMENT',
    seq              BIGINT       NOT NULL COMMENT 'per-(user,scope) 收件序号（notification_seq 计数器写入事务内取号，从 1 起递增）：已读态 = seq ≤ 该范围水位；水位只用本列，禁用 id——分配序≠提交序',
    sender_id        VARCHAR(36)  NULL COMMENT '触发者用户 ID（互动通知：谁评/回/赞了你；广播类为空）',
    type             VARCHAR(20)  NOT NULL COMMENT '类型 ANNOUNCEMENT/AUDIT_RESULT/AUDIT_PENDING/REPORT_RESULT/ORDER_EVENT/CREDIT_CHANGE/POST_COMMENTED/COMMENT_REPLIED/POST_LIKED/COMMENT_LIKED',
    title            VARCHAR(100) NOT NULL COMMENT '标题',
    content          VARCHAR(500) NOT NULL COMMENT '内容',
    ref_type         VARCHAR(20)  NULL COMMENT '关联类型',
    ref_id           VARCHAR(36)  NULL COMMENT '关联业务 ID（POST/COMMENT/PRODUCT/ERRAND 订单均为 UUID 字符串，票 03/05；REPORT=report_id UUID，票 06）',
    broadcast_msg_id VARCHAR(64)  NULL COMMENT '广播消息 msg_id（公告落库幂等键）',
    dedup_key        VARCHAR(100) NULL COMMENT '互动通知幂等键（type+目标+sender）；同一触发者对同一目标只发一条，取消动作不撤回',
    create_time      DATETIME     NOT NULL COMMENT '创建时间',
    update_time      DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_id (notification_id),
    KEY idx_user_scope (user_id, scope, id),
    KEY idx_broadcast (broadcast_msg_id),
    UNIQUE KEY uk_dedup (dedup_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '站内通知表（scope+水位线已读模型，is_read 已退休）';
```

### notification_seq

```sql
CREATE TABLE IF NOT EXISTS notification_seq (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     VARCHAR(36) NOT NULL COMMENT '接收用户 ID',
    scope       VARCHAR(20) NOT NULL COMMENT '收件范围（与 notification.scope 同域）',
    seq         BIGINT      NOT NULL DEFAULT 0 COMMENT '序号计数器：首次取号建行 seq=1，之后写入事务内 UPDATE seq=seq+1 读回，行锁到提交',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_scope (user_id, scope)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '通知收件序号计数器表（每用户每范围一行）';
```

### notification_read

```sql
CREATE TABLE IF NOT EXISTS notification_read (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       VARCHAR(36) NOT NULL COMMENT '接收用户 ID',
    scope         VARCHAR(20) NOT NULL COMMENT '收件范围（每个范围各推各的水位，互不误清）',
    last_read_seq BIGINT      NOT NULL DEFAULT 0 COMMENT '已读水位线：以下（seq≤）全已读以上全未读；推水位=取当前 notification_seq.seq（dedup 幂等忽略的空号一并越过）',
    create_time   DATETIME    NOT NULL COMMENT '创建时间',
    update_time   DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_scope (user_id, scope)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '通知已读水位表（每用户每范围一行）';
```

### errand_order

```sql
CREATE TABLE IF NOT EXISTS errand_order (
    id                BIGINT         NOT NULL AUTO_INCREMENT,
    errand_order_id   VARCHAR(36)    NOT NULL COMMENT '业务 ID（对外唯一标识，UUID 字符串）',
    publisher_id      VARCHAR(36)    NOT NULL COMMENT '下单方用户 ID',
    taker_id          VARCHAR(36)    NULL COMMENT '接单方用户 ID（抢单后填充，接单方取消后清空）',
    lock_seq          INT            NOT NULL DEFAULT 0 COMMENT '锁单轮次（每次抢单 +1；超时中心复合 bizId={orderId}:{lock_seq} 的轮次与回调守卫比对值）',
    type              VARCHAR(20)    NULL COMMENT '类型 EXPRESS_PICKUP/BUY/DELIVER/OTHER（草稿允许为空，提交时必填）',
    description       VARCHAR(200)   NULL COMMENT '任务描述（同步初筛覆盖；草稿允许为空，提交时必填）',
    pickup_location   VARCHAR(100)   NULL COMMENT '取件地点（草稿允许为空，提交时必填）',
    delivery_location VARCHAR(100)   NULL COMMENT '送达地点（草稿允许为空，提交时必填）',
    reward            DECIMAL(10,2)  NULL COMMENT '悬赏（校园币，支付时冻结；草稿允许为空，提交时必填）',
    time_limit_hours  INT            NULL COMMENT '时限（2/4/12/24 小时，支付时起算；草稿允许为空，提交时必填）',
    images            VARCHAR(1000)  NULL COMMENT '配图 URL 列表（JSON 数组，最多 6 张，MinIO）',
    deadline          DATETIME       NULL COMMENT '超时时间（支付时间 + 时限）',
    state             VARCHAR(20)    NOT NULL DEFAULT 'PENDING_PAYMENT' COMMENT '状态 DRAFT(草稿)/PENDING_PAYMENT/PENDING_REVIEW(已付款待审)/PUBLISHED/LOCKED(锁单中)/CONFIRMED(已确认)/PICKED_UP/DELIVERED/COMPLETED/CANCELLED/DISPUTED/REJECTED/DELETED(软删，仅草稿可达)（先付后审：支付转待审，审核通过发布，拒绝转 REJECTED 退款）',
    taker_canceled    TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '接单方取消幂等位（取消过为 1，重试判定用，防重复扣分）',
    version           BIGINT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本（预留）',
    create_time       DATETIME       NOT NULL COMMENT '创建时间',
    update_time       DATETIME       NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_errand_order_id (errand_order_id),
    KEY idx_state_deadline (state, deadline),
    KEY idx_publisher (publisher_id),
    KEY idx_taker (taker_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '跑腿订单表';
```

说明：uuid-biz-id 票 05 起 `errand_order_id` 由应用层发号（原「插 0 回写」退役）。超时链路在新 ID 表示下语义不变：注册侧 `biz_id` 复合编码 `{orderId}:{lockSeq}` 是纯字符串拼接（orderId 现为 UUID，UUID 无冒号，`indexOf(':')` 拆分不受影响；lockSeq 仍 int）；回调侧分片 hash 输入仍是 biz_id 字符串。

### errand_order_event

```sql
CREATE TABLE IF NOT EXISTS errand_order_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    VARCHAR(36)  NOT NULL COMMENT '跑腿订单业务 ID',
    event_type  VARCHAR(30)  NOT NULL COMMENT '事件类型',
    from_state  VARCHAR(20)  NULL     COMMENT '变更前状态（NULL 仅限创建事件）',
    to_state    VARCHAR(20)  NOT NULL COMMENT '变更后状态',
    actor_id    VARCHAR(36)  NULL     COMMENT '操作人用户 ID（仅 SYSTEM 允许 NULL）',
    actor_role  VARCHAR(20)  NOT NULL COMMENT '操作方 PUBLISHER/TAKER/SYSTEM/ADMIN',
    reason_type VARCHAR(50)  NULL     COMMENT '原因类型（仅取消/退出/争议/仲裁类事件写入）',
    reason      VARCHAR(200) NULL     COMMENT '原因补充说明（用户输入，提交时先过同步初筛；SYSTEM 事件恒 NULL）',
    images      VARCHAR(1000) NULL    COMMENT '随附凭证图 URL 列表（JSON 数组，MinIO，INSERT 时定型不可改）',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_order (order_id),
    -- 防占单（票 05）：Redis 降级时按 actor_id + event_type + 近 24h 计主动放弃，是事实源侧唯一查询路径
    KEY idx_actor_type_time (actor_id, event_type, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '跑腿订单状态流转记录（只插入不修改；同订单内 id 递增即状态变更顺序，纠纷溯源事实源）';
```

### product_order

```sql
CREATE TABLE IF NOT EXISTS product_order (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    product_order_id       VARCHAR(36)   NOT NULL COMMENT '业务 ID（对外唯一标识，UUID 字符串）',
    buyer_id               VARCHAR(36)   NOT NULL COMMENT '买家用户 ID',
    seller_id              VARCHAR(36)   NOT NULL COMMENT '卖家用户 ID',
    product_id             VARCHAR(36)   NOT NULL COMMENT '商品业务 ID（引用：跳转/反查/对账用）',
    price                  DECIMAL(10,2) NOT NULL COMMENT '成交价快照',
    product_title          VARCHAR(30)   NULL COMMENT '商品标题快照（下单时写入，此后不可变）',
    product_description    VARCHAR(500)  NULL COMMENT '商品描述快照（下单时写入，此后不可变）',
    product_images         VARCHAR(1000) NULL COMMENT '商品图片 URL 列表快照（JSON 数组，下单时写入，此后不可变）',
    product_trade_location VARCHAR(100)  NULL COMMENT '交易地点快照（下单时写入，此后不可变）',
    product_category       VARCHAR(20)   NULL COMMENT '商品分类快照（下单时写入，此后不可变）',
    state                  VARCHAR(20)   NOT NULL DEFAULT 'PENDING_PAYMENT' COMMENT 'PENDING_PAYMENT 待支付/PAID 已支付待交付/DELIVERED 已交付待确认/COMPLETED 完成/CANCELLED 取消',
    create_time            DATETIME      NOT NULL COMMENT '创建时间',
    update_time            DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_order_id (product_order_id),
    KEY idx_state_created (state, create_time),
    KEY idx_buyer (buyer_id),
    KEY idx_seller (seller_id),
    KEY idx_product (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '二手商品托管订单表';
```

### product_order_event

```sql
CREATE TABLE IF NOT EXISTS product_order_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    VARCHAR(36)  NOT NULL COMMENT '商品订单业务 ID',
    event_type  VARCHAR(30)  NOT NULL COMMENT '事件类型 CREATED/PAID/DELIVERED/COMPLETED/CANCELLED',
    from_state  VARCHAR(20)  NULL     COMMENT '变更前状态（NULL 仅限创建事件）',
    to_state    VARCHAR(20)  NOT NULL COMMENT '变更后状态',
    actor_id    VARCHAR(36)  NULL     COMMENT '操作人用户 ID（仅 SYSTEM 允许 NULL）',
    actor_role  VARCHAR(20)  NOT NULL COMMENT '操作方 BUYER/SELLER/SYSTEM/ADMIN',
    reason_type VARCHAR(50)  NULL     COMMENT '原因类型（仅取消/退出/争议/仲裁类事件写入）',
    reason      VARCHAR(200) NULL     COMMENT '原因补充说明（用户输入，提交时先过同步初筛；SYSTEM 事件恒 NULL）',
    images      VARCHAR(1000) NULL    COMMENT '随附凭证图 URL 列表（JSON 数组，MinIO，INSERT 时定型不可改）',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品订单状态流转记录（只插入不修改；同订单内 id 递增即状态变更顺序，纠纷溯源事实源）';
```

### virtual_wallet

```sql
CREATE TABLE IF NOT EXISTS virtual_wallet (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    wallet_id     VARCHAR(36)   NOT NULL COMMENT '业务 ID（对外唯一标识，UUID 字符串；uuid-biz-id 票 06：应用层 IIdGenerator 随 insert 一次写入）',
    user_id       VARCHAR(36)   NOT NULL COMMENT '用户 ID',
    balance       DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '余额',
    frozen_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '冻结金额（托管中）',
    create_time   DATETIME      NOT NULL COMMENT '创建时间',
    update_time   DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_wallet_id (wallet_id),
    UNIQUE KEY uk_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '虚拟钱包表';
```

### wallet_detail

```sql
CREATE TABLE IF NOT EXISTS wallet_detail (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    wallet_id   VARCHAR(36)   NOT NULL COMMENT '钱包业务 ID（引用 virtual_wallet.wallet_id，UUID 字符串）',
    user_id     VARCHAR(36)   NOT NULL COMMENT '用户 ID（钱包所有者；INCOME 为收款方）',
    type        VARCHAR(20)   NOT NULL COMMENT '类型 RECHARGE/FROZEN/UNFROZEN/INCOME/PRODUCT_FROZEN/PRODUCT_UNFROZEN/PRODUCT_INCOME',
    amount      DECIMAL(10,2) NOT NULL COMMENT '变动金额',
    order_id    VARCHAR(36)   NULL COMMENT '关联订单业务 ID（RECHARGE 为空；uk_order_type 按 type 区分跑腿/商品订单）',
    biz_round   INT           NOT NULL DEFAULT 0 COMMENT '业务轮次：同一订单同类型多轮资金操作的去重维度（预留，当前恒 0）',
    create_time DATETIME      NOT NULL COMMENT '创建时间',
    update_time DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_type (order_id, type, biz_round, user_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '钱包流水表';
```

### user_credit_detail

```sql
CREATE TABLE IF NOT EXISTS user_credit_detail (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     VARCHAR(36) NOT NULL COMMENT '用户 ID',
    `change`    INT         NOT NULL COMMENT '信用分变化（可为负）',
    reason_type VARCHAR(30) NOT NULL COMMENT '原因类型 ERRAND_TAKER_CANCEL/ERRAND_TIMEOUT/REPORT_CONFIRMED/TRADE_COMPLAINT/ADMIN_ADJUST/ADMIN_BAN/ADMIN_UNBAN（取值见 CreditReasonTypeVO）',
    ref_id      VARCHAR(36) NULL COMMENT '关联业务 ID（跑腿=订单；内容类=被罚内容；ADMIN 类为空，不依赖幂等键）',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_reason_ref (user_id, reason_type, ref_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '信用分流水表';
```

### sensitive_word

```sql
CREATE TABLE IF NOT EXISTS sensitive_word (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    word       VARCHAR(50)  NOT NULL COMMENT '敏感词',
    enabled    TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_word (word)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '敏感词库表';
```

### content_review

```sql
CREATE TABLE IF NOT EXISTS content_review (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    review_id     VARCHAR(36)  NOT NULL COMMENT '审核记录业务 ID（UUID 字符串；uuid-biz-id 票 06：应用层 IIdGenerator 随 insert 一次写入；物理自增 id 保留纯内部键）',
    target_type   VARCHAR(20)  NOT NULL COMMENT '审核对象 POST/COMMENT/ERRAND/PRODUCT',
    target_id     VARCHAR(36)  NOT NULL COMMENT '审核对象业务 ID（POST/COMMENT/PRODUCT/ERRAND 均为 UUID 字符串，票 03/05）',
    author_id     VARCHAR(36)  NOT NULL COMMENT '作者用户 ID（冗余；对齐 content_report.author_id，按作者聚合历史裁决免反查业务表）',
    review_round  INT          NOT NULL DEFAULT 1 COMMENT '审核轮次（同一目标第 N 次进入待审，取自业务表的 review_round；COMMENT/ERRAND 恒为 1），重放裁决撞 uk 即幂等跳过',
    action        VARCHAR(20)  NOT NULL COMMENT '动作 APPROVE/REJECT',
    source        VARCHAR(20)  NOT NULL DEFAULT 'MANUAL' COMMENT '触发来源 MANUAL/KEYWORD/RANDOM（KEYWORD=敏感词命中转审、RANDOM=随机抽查；当前仅 MANUAL，其余为扩展点）',
    operator_id   VARCHAR(36)  NOT NULL COMMENT '审核员用户 ID',
    reason        VARCHAR(200) NULL COMMENT '驳回原因/备注',
    reason_code   VARCHAR(50)  NULL COMMENT '拒绝理由枚举 code（APPROVE/枚举化前历史数据为 NULL）',
    create_time   DATETIME     NOT NULL COMMENT '创建时间',
    update_time   DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_id (review_id),
    UNIQUE KEY uk_target_action_round (target_type, target_id, action, review_round),
    KEY idx_create (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审核动作记录表（只插入）';
```

### review_task

审核工单（在途派单凭据；与 content_review 的「只插入审计/历史裁决」语义严格分离：工单单生命周期、可变（领单/回收/裁决），裁决结论落在 content_review，工单只有一个终态 DONE）。uk_target_round 同轮同内容只开一张在途工单，入池 INSERT IGNORE 幂等吸收重复；entered_at 是「业务行进入待审的时刻」而非工单创建时间（回收不改它，等待时长继续累加）：POST/PRODUCT 直取业务表 review_entered_at，COMMENT 取发布时刻、ERRAND 取支付成功时刻。**领单候选/队列 tie-break 口径（uuid-biz-id 票 06）**：task_id 已 UUID 化、不再代表入池先后，FIFO 同刻收敛改用**物理自增 id ASC**（纯内部键承担内部排序，不对外）。

```sql
CREATE TABLE IF NOT EXISTS review_task (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    task_id         VARCHAR(36)  NOT NULL COMMENT '工单业务 ID（UUID 字符串；uuid-biz-id 票 06：应用层 IIdGenerator 随 insert 一次写入；物理自增 id 保留纯内部键，FIFO 同刻 tie-break 用）',
    target_type     VARCHAR(20)  NOT NULL COMMENT '内容类型 POST/COMMENT/PRODUCT/ERRAND（ReviewTargetTypeVO 可审值）',
    target_id       VARCHAR(36)  NOT NULL COMMENT '内容业务 ID（POST/COMMENT/PRODUCT/ERRAND 均为 UUID 字符串，票 03/05）',
    review_round    INT          NOT NULL DEFAULT 1 COMMENT '审核轮次，与 content_review.review_round 同源（COMMENT/ERRAND 恒 1）',
    author_id       VARCHAR(36)  NOT NULL COMMENT '作者用户 ID（冗余，工单详情/历史聚合免 join）',
    source          VARCHAR(20)  NOT NULL DEFAULT 'MANUAL' COMMENT '入池来源 MANUAL/KEYWORD/RANDOM（ReviewSourceVO）',
    claim_state     VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '工单态 PENDING(在池)/CLAIMED(租约中)/DONE(已裁决)；勿与业务表 state 混用',
    claim_by        VARCHAR(36)  NULL COMMENT '当前持单人（审核员用户 ID）',
    claim_seq       INT          NOT NULL DEFAULT 0 COMMENT '领单轮次（每次 claim +1；租约复合 bizId {id}:{claimSeq} 与回调守卫）',
    lease_expire_at DATETIME     NULL COMMENT '租约到期时刻（claim 时 = NOW()+5min）',
    recycle_count   INT          NOT NULL DEFAULT 0 COMMENT '回收次数（≥3 置 escalated + 告警）',
    escalated       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '需人工干预标记（recycle_count≥3）',
    entered_at      DATETIME     NOT NULL COMMENT '进入待审时刻（FIFO 排序/等待时长锚点；POST/PRODUCT 取 review_entered_at，COMMENT 取 create_time，ERRAND 取支付时刻；回收不改）',
    decided_at      DATETIME     NULL COMMENT '裁决时刻（DONE 时写）',
    create_time     DATETIME     NOT NULL COMMENT '创建时间',
    update_time     DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_id (task_id),
    UNIQUE KEY uk_target_round (target_type, target_id, review_round),
    KEY idx_queue (claim_state, entered_at),
    KEY idx_queue_type (claim_state, target_type, entered_at),
    KEY idx_lease (claim_state, lease_expire_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审核工单（在途派单；不承载裁决与可见性）';
```

### content_report

```sql
CREATE TABLE IF NOT EXISTS content_report (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    report_id        VARCHAR(36)   NOT NULL COMMENT '举报单业务 ID（UUID 字符串；uuid-biz-id 票 06：应用层 IIdGenerator 随 insert 一次写入；信用分 REPORT_CONFIRMED 流水 ref_id 引用本列，物理自增 id 保留纯内部键）',
    target_type      VARCHAR(20)   NOT NULL COMMENT '举报对象 POST/COMMENT/USER/MESSAGE（1v1 会话零审核的举报兜底）',
    target_id        VARCHAR(36)   NOT NULL COMMENT '举报对象业务 ID（USER/POST/COMMENT=UUID user_id/内容 id；MESSAGE=msg_id UUID 字符串，uuid-biz-id 票 04）',
    author_id        VARCHAR(36)   NOT NULL COMMENT '被举报人用户 ID（冗余）',
    reporter_id      VARCHAR(36)   NOT NULL COMMENT '举报人用户 ID',
    reason           VARCHAR(200)  NOT NULL COMMENT '举报理由',
    content_snapshot VARCHAR(1000) NULL COMMENT '举报时内容摘要（证据）',
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '状态 PENDING/PROCESSED(核实)/IGNORED(不实)',
    handler_id       VARCHAR(36)   NULL COMMENT '处理人用户 ID（审核员）',
    handled_at       DATETIME      NULL COMMENT '处理时间',
    create_time      DATETIME      NOT NULL COMMENT '创建时间',
    update_time      DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_id (report_id),
    UNIQUE KEY uk_reporter_target (reporter_id, target_type, target_id),
    KEY idx_status (status, create_time),
    KEY idx_target (target_type, target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '举报单表';
```

---

## 2. 营销服务 marketing（库 bs_marketing）

来源：`backend/marketing/sql/schema.sql`（该文件是本节唯一 DDL 事实源，比对脚本 `.scratch/project-rework/cmp_schema_marketing.py`）· 共 18 张表（按该文件 `CREATE TABLE` 逐张清点）

积分（桶模型）、抽奖次数（与积分同构的桶模型）、配置（积分/次数有效期 + 行为返利）、抽奖（活动/策略/奖品/记录）、库存（虚拟权益池与实物营销配额的两段式库存：进程内合并 + DB 台账）、本服务 outbox。原积分与抽奖两个服务合并为一个营销服务、共库，积分流水与抽奖记录可同库同事务。**uuid-biz-id 票 07**：营销自有业务键（bucket_id / activity_id / strategy_id / award_id / record_id / inv_id / 兑换 ref_id / 台账 biz_id）全 `VARCHAR(36)`，由工程内 `IIdGenerator`（`IdGeneratorConfig` @Bean）insert 前发号一次写入；抽奖记录/兑换单两个幂等键由 `IdempotencyKeys.of()` 确定性映射（SHA-256(userId, token) 16 字节组 UUID，36 位规范形）——同一 token 恒同一 id，重放语义不变。`draw_strategy_award.inv_id` 的「不限量」哨兵由数值 0 改字符串 `'0'`（`RaffleDrawServiceImpl` 按字符串等值判断；落库快照无库存来源仍置 NULL）；`draw_strategy_weight.weights` 随 award_id 变长放宽到 VARCHAR(512)。存量数据不迁移，删库重建。

### points_bucket

```sql
CREATE TABLE IF NOT EXISTS points_bucket (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    bucket_id   VARCHAR(36) NOT NULL COMMENT '业务 ID（对外唯一标识）',
    user_id     VARCHAR(36) NOT NULL COMMENT '用户 ID（跨 schema 只存 id，不 join 单体库）',
    amount      INT         NOT NULL COMMENT '入账积分数（恒正；方向由「在哪张表 + 哪个 reason_type」表达）',
    remain      INT         NOT NULL COMMENT '该桶剩余可用积分（消费按 FIFO 递减，过期清理置 0）',
    expire_at   DATETIME    NOT NULL COMMENT '过期时间 = 入账时点 + points_validity_config.points_valid_days',
    reason_type VARCHAR(30) NOT NULL COMMENT '积分来源 LOTTERY_WIN(抽奖中奖) / REBATE(行为返利：签到/支付等，规则见 action_rebate_config，ref_id=发放幂等键)',
    ref_id      VARCHAR(36) NOT NULL COMMENT '来源业务 ID（LOTTERY_WIN = 抽奖记录 id）',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bucket_id (bucket_id),
    UNIQUE KEY uk_user_reason_ref (user_id, reason_type, ref_id) COMMENT '入账幂等键：MQ 重投不重复加分',
    KEY idx_user_expire (user_id, expire_at) COMMENT '余额求和与 FIFO 消费（WHERE user_id=? AND remain>0 AND expire_at>NOW() ORDER BY expire_at, id）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分桶（入账流水 + 事实源）';
```

### points_detail

```sql
CREATE TABLE IF NOT EXISTS points_detail (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     VARCHAR(36) NOT NULL COMMENT '用户 ID',
    bucket_id   VARCHAR(36) NOT NULL COMMENT '被扣的桶业务 ID（FIFO 顺序）',
    amount      INT         NOT NULL COMMENT '本次从该桶扣掉的积分数（恒正）',
    reason_type VARCHAR(30) NOT NULL COMMENT '消费原因 REDEEM_DRAW_COUNT(兑换抽奖次数) / REDEEM_PRIZE(兑换奖品)',
    ref_id      VARCHAR(36) NOT NULL COMMENT '来源业务 ID（REDEEM_DRAW_COUNT = 兑换单 id，与 draw_count_bucket.ref_id 同值同事务；REDEEM_PRIZE = 抽奖记录 id，draw_record.record_id）',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bucket_reason_ref (bucket_id, reason_type, ref_id) COMMENT '同一桶同一笔业务不重复扣',
    KEY idx_user_ref (user_id, reason_type, ref_id) COMMENT '兑换边幂等预检',
    KEY idx_bucket (bucket_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分消费明细';
```

### draw_count_bucket

```sql
CREATE TABLE IF NOT EXISTS draw_count_bucket (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    bucket_id   VARCHAR(36) NOT NULL COMMENT '业务 ID（对外唯一标识）',
    user_id     VARCHAR(36) NOT NULL COMMENT '用户 ID',
    amount      INT         NOT NULL COMMENT '入账次数（恒正）',
    remain      INT         NOT NULL COMMENT '该桶剩余可用次数（消费按 FIFO 递减，过期清理置 0）',
    expire_at   DATETIME    NOT NULL COMMENT '过期时间 = 入账时点 + draw_count_config.draw_count_valid_days',
    reason_type VARCHAR(20) NOT NULL COMMENT '次数来源 REDEEM(积分兑换) / REBATE(行为返利：签到/支付等，规则见 action_rebate_config，ref_id=发放幂等键)',
    ref_id      VARCHAR(36) NOT NULL COMMENT '来源业务 ID（REDEEM = 兑换单 id，与 points_detail.ref_id 的 REDEEM_DRAW_COUNT 同值、同事务写入）',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bucket_id (bucket_id),
    UNIQUE KEY uk_user_reason_ref (user_id, reason_type, ref_id) COMMENT '入账幂等键：兑换重试不重复加次数',
    KEY idx_user_expire (user_id, expire_at) COMMENT '剩余次数求和与 FIFO 消费（WHERE user_id=? AND remain>0 AND expire_at>NOW() ORDER BY expire_at, id）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖次数桶（与积分桶同构）';
```

### draw_count_detail

```sql
CREATE TABLE IF NOT EXISTS draw_count_detail (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     VARCHAR(36) NOT NULL COMMENT '用户 ID',
    bucket_id   VARCHAR(36) NOT NULL COMMENT '被扣的桶业务 ID（FIFO 顺序）',
    ref_id      VARCHAR(36) NOT NULL COMMENT '抽奖记录业务 ID（draw_record.record_id，消费幂等键；每次抽奖扣 1 次）',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bucket_ref (bucket_id, ref_id) COMMENT '同一桶同一笔抽奖不重复扣',
    KEY idx_ref (ref_id) COMMENT '按抽奖记录反查扣次明细'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖次数消费明细';
```

### points_validity_config

```sql
CREATE TABLE IF NOT EXISTS points_validity_config (
    id                BIGINT   NOT NULL AUTO_INCREMENT,
    points_valid_days INT      NOT NULL COMMENT '积分有效期天数（积分入账时算 expire_at）',
    create_time       DATETIME NOT NULL COMMENT '创建时间',
    update_time       DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分配置（单行）';
```

### draw_count_config

```sql
CREATE TABLE IF NOT EXISTS draw_count_config (
    id                    BIGINT   NOT NULL AUTO_INCREMENT,
    draw_count_valid_days INT      NOT NULL COMMENT '抽奖次数有效期天数（次数入账时算 expire_at）',
    create_time           DATETIME NOT NULL COMMENT '创建时间',
    update_time           DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖次数配置（单行）';
```

### action_rebate_config

```sql
CREATE TABLE IF NOT EXISTS action_rebate_config (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    action_type  VARCHAR(16)  NOT NULL COMMENT '行为类型 sign(签到) / pay(支付)',
    rebate_type  VARCHAR(16)  NOT NULL COMMENT '返利类型 DRAW_COUNT(抽奖次数) / POINTS(用户活动积分)',
    rebate_name  VARCHAR(64)  NOT NULL COMMENT '返利名称',
    rebate_desc  VARCHAR(128) NULL COMMENT '返利描述',
    rebate_value INT          NOT NULL DEFAULT 0 COMMENT '返利值【抽奖次数、积分值】',
    config_id    BIGINT       NOT NULL COMMENT '有效期配置 id（rebate_type=POINTS → points_validity_config.id；DRAW_COUNT → draw_count_config.id）',
    state        VARCHAR(12)  NOT NULL COMMENT '状态 open(开启) / close(关闭)',
    create_time  DATETIME     NOT NULL COMMENT '创建时间',
    update_time  DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_action_rebate (action_type, rebate_type) COMMENT '同一行为同一返利类型一条规则；要多档返利时去掉此键',
    KEY idx_config (config_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '行为返利配置表（签到/支付等行为发放抽奖次数/积分，入账落对应桶表）';
```

抽奖部分：活动 / 策略 / 奖品配置与抽奖记录，以及虚拟库存（积分、抽奖次数）与实物营销配额的两段式库存（进程内合并 + DB 台账）。

### draw_activity

```sql
CREATE TABLE IF NOT EXISTS draw_activity (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    activity_id     VARCHAR(36) NOT NULL COMMENT '业务 ID（对外唯一标识）',
    activity_name   VARCHAR(64) NOT NULL COMMENT '活动名称',
    strategy_id     VARCHAR(36) NOT NULL COMMENT '抽奖策略 ID',
    begin_date_time DATETIME    NOT NULL COMMENT '开始时间',
    end_date_time   DATETIME    NOT NULL COMMENT '结束时间',
    state           VARCHAR(16) NOT NULL DEFAULT 'create' COMMENT '活动状态 create/open/close',
    create_time     DATETIME    NOT NULL COMMENT '创建时间',
    update_time     DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_id (activity_id),
    UNIQUE KEY uk_strategy_id (strategy_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖活动表';
```

### draw_strategy

```sql
CREATE TABLE IF NOT EXISTS draw_strategy (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    strategy_id   VARCHAR(36)  NOT NULL COMMENT '业务 ID（对外唯一标识）',
    strategy_desc VARCHAR(128) NULL COMMENT '抽奖策略描述',
    rule          VARCHAR(256) NULL COMMENT '规则模型（逗号分隔的规则链，如 rule_blacklist,rule_weight）',
    create_time   DATETIME     NOT NULL COMMENT '创建时间',
    update_time   DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_strategy_id (strategy_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖策略表';
```

### draw_strategy_award

```sql
CREATE TABLE IF NOT EXISTS draw_strategy_award (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    strategy_id VARCHAR(36)  NOT NULL COMMENT '所属抽奖策略 ID',
    award_id    VARCHAR(36)  NOT NULL COMMENT '奖品 ID（策略内唯一）',
    award_name  VARCHAR(128) NOT NULL COMMENT '奖品名称',
    award_desc  VARCHAR(255) NULL COMMENT '奖品描述',
    award_type  VARCHAR(20)  NOT NULL COMMENT '奖品类型 VIRTUAL(虚拟资产：积分、抽奖次数) / PHYSICAL(实物资产) / THANKS(谢谢参与占位：award_rate 计入概率池、type=normal、inv_id=''0'' 无库存)',
    award_rate  DECIMAL(6,4) NOT NULL COMMENT '中奖概率（如 0.0500 = 5%；同一策略内各奖品位概率合计 = 1.0000）',
    level       INT          NOT NULL COMMENT '奖项层级（1 等奖 = 1，越大越低）',
    inv_id      VARCHAR(36)  NOT NULL DEFAULT '0' COMMENT '关联库存业务 ID（''0'' = 无库存/不限量）',
    type        VARCHAR(20)  NOT NULL DEFAULT 'normal' COMMENT '奖品位类型 normal(正常) / fallback(兜底) / blacklist(黑名单)',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序值',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_strategy_award (strategy_id, award_id),
    KEY idx_inv (inv_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖策略奖品配置表';
```

> `inv_id` 哨兵 `'0'`（字符串，注释里 `''0''` 转义）：不限量档。查询/代码侧一律字符串等值（`a.inv_id = '0'`，带引号防 VARCHAR 隐式转 0）；`draw_record.inv_id` 落库快照对不限量档置 NULL（「无库存来源」比哨兵更好判）。

### draw_strategy_weight

```sql
CREATE TABLE IF NOT EXISTS draw_strategy_weight (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    strategy_id VARCHAR(36)  NOT NULL COMMENT '所属抽奖策略 ID',
    count       INT          NOT NULL COMMENT '权重档的抽数阈值：用户在该策略下累计抽数 ≥ count 时此档生效，多档取满足阈值中 count 最大的一档（累计抽数 = draw_record 中该 user_id + strategy_id 的行数）',
    weights     VARCHAR(512) NOT NULL COMMENT '该档保底奖品池（逗号分隔的 award_id 列表，指向 draw_strategy_award.award_id；命中档位后在池内按 award_rate 归一化概率出奖）',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_strategy_id_count (strategy_id, count),
    KEY idx_strategy_id_count_weights (strategy_id, count, weights)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖策略权重表（按累计抽数分档的保底奖品池；原设计稿名 strategy_weight，落库时按本文件命名与主键口径规范化，见票 11）';
```

### draw_record

```sql
CREATE TABLE IF NOT EXISTS draw_record (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    record_id   VARCHAR(36)  NOT NULL COMMENT '业务 ID（对外唯一标识；points_bucket.ref_id 的 LOTTERY_WIN 指向它）',
    user_id     VARCHAR(36)  NOT NULL COMMENT '用户 ID',
    activity_id VARCHAR(36)  NOT NULL COMMENT '抽奖活动 ID',
    strategy_id VARCHAR(36)  NOT NULL COMMENT '抽奖策略 ID',
    award_id    VARCHAR(36)  NOT NULL COMMENT '命中的奖品 ID（策略内唯一）',
    award_name  VARCHAR(128) NOT NULL COMMENT '奖品名称快照（抽中时写入，此后不可变）',
    award_desc  VARCHAR(255) NULL COMMENT '奖品描述快照（抽中时写入，此后不可变）',
    award_type  VARCHAR(20)  NOT NULL COMMENT '奖品类型快照 VIRTUAL(虚拟资产) / PHYSICAL(实物资产) / THANKS(谢谢参与)',
    level       INT          NOT NULL COMMENT '奖项层级快照（1 等奖 = 1，越大越低）',
    inv_id      VARCHAR(36)  NULL COMMENT '发放来源库存/配额业务 ID（快照；无库存来源为 NULL）',
    state       VARCHAR(16)  NOT NULL DEFAULT '待兑换' COMMENT '奖品状态 待兑换/已兑换/已发货/已完成（THANKS 行落库即已完成，不走兑换/发货）',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_record_id (record_id),
    KEY idx_user_created (user_id, create_time) COMMENT '我的抽奖记录：create_time DESC 翻页',
    KEY idx_inv_id (inv_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖记录表';
```

### draw_record_detail

```sql
CREATE TABLE IF NOT EXISTS draw_record_detail (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     VARCHAR(36) NOT NULL COMMENT '用户 ID（冗余自 draw_record，按人查询/鉴权用）',
    record_id   VARCHAR(36) NOT NULL COMMENT '抽奖记录业务 ID（draw_record.record_id）',
    type        VARCHAR(20) NOT NULL COMMENT '动作类型 DRAW(抽中落库，对应 state=待兑换) / EXCHANGE(兑换，→已兑换) / SHIP(发货，→已发货) / COMPLETE(完成，→已完成)：落库与 state 每推进一步各落一条',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_record_type (record_id, type) COMMENT '同一记录同一动作只落一条：state 推进重试幂等',
    KEY idx_user_created (user_id, create_time) COMMENT '按人查动作历史'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖记录动作明细（只插入：创建 draw_record 时同事务落 DRAW，此后 state 每推进一步落一条）';
```

### virtual_inventory_quota

```sql
CREATE TABLE IF NOT EXISTS virtual_inventory_quota (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    inv_id      VARCHAR(36)  NOT NULL COMMENT '业务 ID（库存全局唯一标识）',
    name        VARCHAR(128) NOT NULL COMMENT '库存/权益名称，如「暑期签到积分池」',
    type        VARCHAR(20)  NOT NULL COMMENT '虚拟资产类型 POINTS(积分) / DRAW_COUNT(抽奖次数)',
    value       INT          NOT NULL DEFAULT 0 COMMENT '单次发放面额，如 50 积分、1 次抽奖机会',
    surplus_qty BIGINT       NOT NULL DEFAULT -1 COMMENT '剩余可发份数，-1=不限量',
    issued_qty  BIGINT       NOT NULL DEFAULT 0 COMMENT '累计已发放份数，用于对账/预算',
    state       VARCHAR(12)  NOT NULL DEFAULT 'open' COMMENT 'open/closed/exhausted',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_id (inv_id),
    KEY idx_state (state),
    CONSTRAINT chk_virtual_inventory_quota CHECK (surplus_qty >= -1 AND value >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '虚拟库存配额（积分/抽奖次数权益池）';
```

### virtual_inventory_quota_detail

```sql
CREATE TABLE IF NOT EXISTS virtual_inventory_quota_detail (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    inv_id      VARCHAR(36) NOT NULL COMMENT '配额业务 ID',
    quantity    BIGINT      NOT NULL DEFAULT 0 COMMENT '本次预扣数量',
    biz_type    VARCHAR(32) NOT NULL COMMENT '业务类型：LOTTERY_DRAW（抽奖预扣）',
    biz_id      VARCHAR(36) NOT NULL COMMENT '业务 ID（抽奖 = draw_record.record_id）',
    create_time DATETIME    NOT NULL COMMENT '插入时间（DB NOW()）',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz (biz_type, biz_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '虚拟库存配额扣减明细（进程内合并的台账；撞 uk = 已落过，按成功回放）';
```

说明（合并落库，两段同一套）：预扣与兑换确认走进程内合并器 `QuotaMergeBuffer`（hot-row-merge-l2w256，ADR-0013 取代 0011/0012），请求等 5ms 窗口后**一组一个事务**：明细 `INSERT IGNORE` + 一条台账 `UPDATE`，`INSERT` 后台账同事务可见——不再有 Redis 桶、合并消息、`state` 列与 60 秒兜底。撞 `uk_biz` = 已落过，按成功回放。uuid-biz-id 票 07 起 `inv_id`/`biz_id` 为 UUID 字符串，合并分片键 `MergeConfig.shardOf(String)` 按字符串 hashCode 高低位折叠（shardCount 仍 2 的幂）。

### physical_marketing_quota

```sql
CREATE TABLE IF NOT EXISTS physical_marketing_quota (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    inv_id      VARCHAR(36)  NOT NULL COMMENT '业务 ID（配额全局唯一标识）',
    name        VARCHAR(128) NOT NULL COMMENT '配额名称，如「暑期实物营销配额」',
    decr        VARCHAR(255) NULL COMMENT '配额说明',
    surplus_qty BIGINT       NOT NULL DEFAULT -1 COMMENT '剩余可发份数，-1=不限量',
    waiting_qty BIGINT       NOT NULL DEFAULT 0 COMMENT '预扣数量（预扣已提交、用户尚未兑换的部分）',
    issued_qty  BIGINT       NOT NULL DEFAULT 0 COMMENT '已占用数量（用户已兑换、正式出库的部分）',
    state       VARCHAR(12)  NOT NULL DEFAULT 'open' COMMENT 'open/closed/exhausted',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_id (inv_id),
    KEY idx_state (state),
    CONSTRAINT chk_quota_surplus CHECK (surplus_qty >= -1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '实物营销配额（预扣 → 占用的两段式库存）';
```

### physical_marketing_quota_detail

```sql
CREATE TABLE IF NOT EXISTS physical_marketing_quota_detail (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    inv_id      VARCHAR(36) NOT NULL COMMENT '配额业务 ID',
    quantity    BIGINT      NOT NULL DEFAULT 0 COMMENT '本次流转数量',
    type        TINYINT     NOT NULL COMMENT '1-预扣结转（进 waiting）2-兑换确认（waiting → issued）',
    biz_type    VARCHAR(32) NOT NULL COMMENT '业务类型：LOTTERY_DRAW（预扣）/ LOTTERY_CLAIM（兑换确认）',
    biz_id      VARCHAR(36) NOT NULL COMMENT '业务 ID（抽奖与兑换确认都用 draw_record.record_id）',
    create_time DATETIME    NOT NULL COMMENT '插入时间（DB NOW()）',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_type (type, biz_type, biz_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '实物营销配额流转明细（预扣结转 / 兑换确认两类，进程内合并的台账；uk_biz_type 是唯一幂等裁决者）';
```

说明（合并落库，与虚拟段同一套）：type=1（预扣结转）合进 waiting_qty、同时减 surplus_qty；type=2（兑换确认）在返回时就把 waiting_qty 转成 issued_qty（与预扣同分片、同形状的另一组，一个事务），**不碰任何 Redis 结构**。撞 `uk_biz_type` = 已落过，按成功回放。hot-row-merge-l2w256 切换期的存量过渡形状处置（bucket_id / state 列与两条索引的 window-ddl / d9-finalize 两脚本）已随 uuid-biz-id 票 08 退役：存量数据不迁移，删库重建。

### outbox_message

```sql
CREATE TABLE IF NOT EXISTS outbox_message (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    msg_id      VARCHAR(64) NOT NULL COMMENT '消息ID（幂等/去重）',
    destination VARCHAR(64) NOT NULL COMMENT '消息目的地（destination 语法：topic 或 topic:tag，由业务端自行解析）',
    payload     TEXT        NOT NULL COMMENT '消息内容 JSON',
    delay_level TINYINT     NOT NULL DEFAULT 0 COMMENT 'RocketMQ 延迟等级；0=不延迟，1=1 秒（约 1.0–1.1s）',
    delay_ms    INT         NULL COMMENT '毫秒级相对延迟（RocketMQ 定时消息）；NULL=走 delay_level',
    state       TINYINT     NOT NULL DEFAULT 0 COMMENT '0待发送 1已发送 2发送失败(重试耗尽)',
    retry_count INT         NOT NULL DEFAULT 0 COMMENT '已重试次数',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_id (msg_id),
    KEY idx_state (state)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '本地消息表(outbox)';
```
