-- better-school 核心 schema：建表 DDL + 种子数据，不再含 CREATE DATABASE / USE（库由调用方先选定）。
-- 开发库与测试库共用本文件——各自一份建库文件在 init-dev.sql / init-test.sql，两段必须按序拼接喂给同一个连接。
-- 表结构与根目录 sql.md 第 1 节逐表一致（唯一事实源）：主键恒为无业务含义的物理自增 id，
-- 业务标识单独成列并配 uk；业务 ID 是应用层发号的 UUID 字符串（VARCHAR(36)），与物理 id 永不同值、
-- 永不对外暴露物理 id（uuid-biz-id 改造；存量数据不迁移，删库重建）。（**主键口径例外**：各域 feed 索引表 post/product/errand_feed_index 与
-- 评论热评索引 post_comment_feed_index 都是投影表、不设独立业务列，主键直接取业务键）；每表
-- create_time/update_time 收尾（不带默认值，由应用写入），全库无 created_at/updated_at
-- （例外共两处：timeout_task_00..15 的 created_at/updated_at——按原样自原独立超时库迁入，见该节注释；
--   各域 feed 索引表（post/product/errand_feed_index）只留 update_time（feed_time 承担行诞生时刻）、
--   各域浏览明细表只留 viewed_at——时间列按各自需要收窄，见对应表注释）。
-- 容器 entrypoint 内 mysql CLI 默认 latin1，中文种子数据会乱码入库，先固定连接字符集
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS bs_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       VARCHAR(36)       NOT NULL COMMENT '业务 ID（对外唯一标识）',
    phone         VARCHAR(20)  NOT NULL COMMENT '手机号，登录凭证',
    nickname      VARCHAR(50)  NOT NULL COMMENT '昵称',
    avatar        VARCHAR(255) NULL COMMENT '头像 URL（MinIO）',
    signature     VARCHAR(60)  NULL COMMENT '个性签名（可空，≤40 字，空即不展示）',
    gender        VARCHAR(10)  NULL COMMENT '性别 MALE/FEMALE，NULL=不展示',
    show_liked    TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '隐私开关：公开我的"赞过"（1 公开 / 0 仅自己，仅他视生效）',
    show_comments TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '隐私开关：公开我的"评论"（1 公开 / 0 仅自己，仅他视生效）',
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色 USER/ADMIN',
    state         VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT '状态 NORMAL/BANNED',
    credit_score  INT          NOT NULL DEFAULT 100 COMMENT '信用分',
    last_login_at DATETIME     NULL COMMENT '最近登录时间',
    create_time   DATETIME     NOT NULL COMMENT '创建时间',
    update_time   DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_id (user_id),
    UNIQUE KEY uk_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

-- 系统助手账号（通知 CHAT 通道发送者，Constants.SYSTEM_USER_ID 固定 ID）：
-- 订单动态/信用分等留档类通知由它经 chat 发送管线落进用户「系统助手」1v1 会话；
-- phone 占位不参与登录，role=ADMIN 避免被普通用户维度枚举逻辑误伤
INSERT INTO bs_user (user_id, phone, nickname, role, state, credit_score, create_time, update_time)
SELECT '00000000-0000-0000-0000-000000000001', '00000000000', '系统助手', 'ADMIN', 'NORMAL', 100, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM bs_user WHERE user_id = '00000000-0000-0000-0000-000000000001');

CREATE TABLE IF NOT EXISTS post (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    post_id       VARCHAR(36)   NOT NULL COMMENT '业务 ID（对外唯一标识，UUID 字符串；物理自增 id 只作内部键）',
    user_id       VARCHAR(36)        NOT NULL COMMENT '发布者用户 ID',
    title         VARCHAR(100)  NULL COMMENT '标题（可选）',
    content       TEXT          NULL COMMENT '正文（草稿可空，提交时校验必填）',
    images        VARCHAR(1000) NULL COMMENT '图片 URL 列表（JSON 数组，MinIO 后置）',
    cover         VARCHAR(500)  NULL COMMENT '独立封面 URL（可空，可传相册外的独立上传图；展示口径：独立封面 ?? 相册第一张）',
    state         VARCHAR(20)   NOT NULL DEFAULT 'NORMAL' COMMENT '状态 NORMAL/DRAFT(草稿)/DELETED（发布落库即对外可见，未发布进 DRAFT，提交转 NORMAL）',
    expire_at     DATETIME      NULL COMMENT '过期归档时间（满 30 天下架，XXL-Job 后置）',
    create_time   DATETIME      NOT NULL COMMENT '创建时间',
    update_time   DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_post_id (post_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '帖子表';
-- post.category / idx_category_created 已删（feed-index-uv 票 07 收口：帖子分类退役，墙内容发现 =
-- 时间线 + 搜索；商品的 category 是市集真需求，不受影响）；
-- post.view_count 已删（同票：浏览切 UV 口径，真相源 user_view_history、加速列 post_view_count）。
-- 存量库不迁移（uuid-biz-id：删库重建），历史删列/割接脚本已随 uuid-biz-id 票 08 退役。

CREATE TABLE IF NOT EXISTS post_comment (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    comment_id  VARCHAR(36)  NOT NULL COMMENT '业务 ID（对外唯一标识，UUID 字符串）',
    post_id     VARCHAR(36)  NOT NULL COMMENT '帖子业务 ID',
    user_id     VARCHAR(36)       NOT NULL COMMENT '评论者用户 ID',
    content     VARCHAR(500)  NULL COMMENT '评论文字（可空：纯图评论时为空，与 images 至少有一个非空）',
    images      VARCHAR(1000) NULL COMMENT '图片 URL 列表（JSON 数组，最多 6 张，MinIO）',
    state       VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT '状态 NORMAL/DELETED（发布即 NORMAL；列表只查 NORMAL）',
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
-- post_comment.like_count 已删（comment-count-narrow-table 票 04 收口：评论点赞计数切窄表，真源
-- post_comment_like_count、排序快照 post_comment_feed_index）。删列前旧列已在票 02 停写停读；
-- 存量点赞不搬（用户确认接受：历史评论点赞显示 0），删列即丢弃。
-- 存量库不迁移（uuid-biz-id：删库重建），历史割接脚本已随 uuid-biz-id 票 08 退役。

CREATE TABLE IF NOT EXISTS product (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    product_id     VARCHAR(36)    NOT NULL COMMENT '业务 ID（对外唯一标识，UUID 字符串）',
    user_id        VARCHAR(36)         NOT NULL COMMENT '卖家用户 ID',
    title          VARCHAR(30)    NULL COMMENT '标题（草稿允许为空，提交时必填）',
    description    VARCHAR(500)   NULL COMMENT '描述',
    images         VARCHAR(1000)  NULL COMMENT '图片 URL 列表（JSON 数组，MinIO 后置）',
    price          DECIMAL(10,2)  NULL COMMENT '价格（草稿允许为空，提交时必填）',
    trade_location VARCHAR(100)   NULL COMMENT '交易地点（校区+场所）（草稿允许为空，提交时必填）',
    category       VARCHAR(20)    NULL COMMENT '分类 DIGITAL/BOOKS/DAILY/SPORTS/TICKETS/OTHER（草稿允许为空，提交时必填）',
    state          VARCHAR(20)    NOT NULL DEFAULT 'ON_SALE' COMMENT '状态 DRAFT(草稿)/ON_SALE/LOCKED(交易中)/SOLD/DELETED（发布落库即在售，未发布进 DRAFT，提交转 ON_SALE）',
    expire_at      DATETIME       NULL COMMENT '到期下架时间（上架时刷新满 30 天，XXL-Job 后置汇入草稿箱）',
    create_time    DATETIME       NOT NULL COMMENT '创建时间',
    update_time    DATETIME       NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_id (product_id),
    KEY idx_category_created (category, create_time),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '二手商品表';
-- product.view_count 已删（market-feed-uv 票 06 收口：浏览切 UV 口径，真相源 product_view_history、
-- 加速列 product_view_count；存量 PV 随票 01 的一次性清零脚本废弃，清零脚本与列一同退役）。
-- 存量库不迁移（uuid-biz-id：删库重建），历史删列脚本已随 uuid-biz-id 票 08 退役。

-- 库存模型（multi-quantity-listing）：同款多件挂单 = 台账 + 流水双层。remaining 是防超卖唯一真源，
-- 下单事务内条件 UPDATE（remaining >= 扣减量）原子扣减，扣到 0 同事务打 product.state = SOLD（feed/搜索同步随行）；
-- 取消/支付超时关单回补，回补后 remaining > 0 且 state = SOLD 时同事务翻回 ON_SALE。
-- 订单流不再使用 LOCKED（一件挂单的"交易中"表达不了"3 件锁了 1 件"），旧态保留仅兼容存量行。
CREATE TABLE IF NOT EXISTS product_inventory (
    product_id  VARCHAR(36) NOT NULL COMMENT '商品业务 ID（product.product_id，1:1 行随发布/存草稿诞生）',
    quantity    INT         NOT NULL DEFAULT 1 COMMENT '挂单总件数（发布时定，草稿可改，上架后仅能经加货上调）',
    remaining   INT         NOT NULL DEFAULT 1 COMMENT '剩余可售件数（下单扣减、关单回补；恒 <= quantity）',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品库存台账（remaining 是防超卖唯一真源；流水见 product_stock_event）';

-- 库存流水（multi-quantity-listing）：只插入不修改，与台账变更同事务双写；after_remaining 串起来可对账，
-- 漂移时按流水重建 remaining（同 view_count 重建 job 套路）。订单驱动的扣减/回补带 order_id，加货/校正为 NULL。
CREATE TABLE IF NOT EXISTS product_stock_event (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    product_id      VARCHAR(36)  NOT NULL COMMENT '商品业务 ID',
    event_type      VARCHAR(20)  NOT NULL COMMENT 'INIT 发布建账 / DEDUCT 下单占用 / RELEASE 关单回补 / RESTOCK 卖家加货 / ADJUST 校正（含标记已出清零）',
    delta           INT          NOT NULL COMMENT 'remaining 变动量（扣减为负、回补/加货为正、清零为负）',
    after_remaining INT          NOT NULL COMMENT '事件后 remaining 快照（对账锚点）',
    order_id        VARCHAR(36)  NULL     COMMENT '关联订单业务 ID（DEDUCT/RELEASE 必填，其余 NULL）',
    actor_id        VARCHAR(36)  NULL     COMMENT '操作人用户 ID（SYSTEM 事件可 NULL）',
    actor_role      VARCHAR(20)  NOT NULL COMMENT '操作方 BUYER/SELLER/SYSTEM/ADMIN',
    create_time     DATETIME     NOT NULL COMMENT '创建时间',
    update_time     DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_product (product_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品库存流水（只插入不修改；与 product_inventory 变更同事务双写，审计/对账用，不参与用户时间线）';

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

-- 计数决策（sql.md 说明节）：点赞事务 = INSERT post_like（uk 幂等）+ 投递计数消息（至少一次）→
-- 消费端按帖子聚合窗口内的 ±1 增量，合并成一条 UPDATE 落本表。展示值短暂滞后于刷盘窗口（最终一致）；取消点赞发负增量。
-- 消息级幂等键是生产端生成的 msgId（随载荷走），不是 post_like 的 uk：like→unlike→like 是合法序列，
-- 按关系键去重会把第三次点赞误判为重投而丢增量。
CREATE TABLE IF NOT EXISTS post_like_count (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    post_id     VARCHAR(36)   NOT NULL COMMENT '帖子业务 ID（唯一）',
    like_count  INT      NOT NULL DEFAULT 0 COMMENT '点赞数（MQ 异步合并刷盘落此表；点赞/取消的明细关系在 post_like）',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_post (post_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '帖子点赞计数表（专用窄行，不与 post 内容/状态写入抢锁）';

CREATE TABLE IF NOT EXISTS post_comment_count (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    post_id       VARCHAR(36)   NOT NULL COMMENT '帖子业务 ID（唯一）',
    comment_count INT      NOT NULL DEFAULT 0 COMMENT '评论数（评论落库同事务 +1、删评 -1，只计 NORMAL 态）',
    create_time   DATETIME NOT NULL COMMENT '创建时间',
    update_time   DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_post (post_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '帖子评论计数表（专用窄行，评论同步 +1 不再锁 post 行）';

-- 评论点赞计数真源（comment-count-narrow-table 票 02）：全量评论都入表，根评论与子回复同形。
-- 行诞生于**评论发布事务**（INSERT IGNORE，affected = 0 落 warn），因此不变式是「行诞生于评论发布」而不是
-- 「行诞生于第一次点赞」——点赞 UPDATE 命中 0 行只落 warn 交手工对账，不 ODKU 兜底补行。
-- 无二级索引：取数全走 comment_id 唯一键点查，热评排序走 post_comment_feed_index（本表 like_count 索引
-- 给不出「单帖内按赞数排序」要的全站序，票 02 拍板不建）。
CREATE TABLE IF NOT EXISTS post_comment_like_count (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    comment_id  VARCHAR(36)   NOT NULL COMMENT '评论业务 ID（唯一；根评论与子回复都入表）',
    like_count  INT      NOT NULL DEFAULT 0 COMMENT '评论点赞数（点赞合并事务/取消赞同事务直写；GREATEST(0,±N) clamp；真源 = post_comment_like 条数）',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_comment (comment_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评论点赞计数表（专用窄行，不与评论内容/状态写入抢锁；行诞生于评论发布事务）';

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

-- 根评论热评排序投影（comment-count-narrow-table 票 01）：照帖子 feed 形态，只做「单帖内按赞数取页」一件事。
-- 行集 = 当前可见根评论：发布事务 INSERT、软删事务物理 DELETE——评论软删是终态、没有恢复路径，
-- 所以不像帖子那样「行常驻 + state 快照镜像」（那列在评论这里只有 NORMAL，没有维护价值）。
-- 子回复不入索引（楼内不按赞排，只展示赞数）。主键口径照 feed 索引先例（见文件头的主键例外）。
CREATE TABLE IF NOT EXISTS post_comment_feed_index (
    comment_id  VARCHAR(36)   NOT NULL COMMENT '根评论业务 ID（唯一；子回复不入索引）',
    post_id     VARCHAR(36)   NOT NULL COMMENT '所属帖子业务 ID（「最热」按此前缀把扫描锁在单帖）',
    like_count  INT      NOT NULL DEFAULT 0 COMMENT '点赞数快照（点赞/取消赞同事务旁带 ±N，真源 post_comment_like_count——票 02 前为主列 post_comment.like_count；UPDATE 落 0 行落日志交重建 job；「最热」排序键）',
    create_time DATETIME NOT NULL COMMENT '创建时间（= 评论发布时刻）：点赞数同值时按它升序收敛成确定序（发布先后；UUID 无序，不能再拿业务 ID 当时间代理）',
    update_time DATETIME NOT NULL COMMENT '更新时间（技术时间戳，禁止当排序键）',
    PRIMARY KEY (comment_id),
    KEY idx_post_like (post_id, like_count, create_time, comment_id) COMMENT '「最热」：单帖前缀扫描收窄范围（覆盖索引不回表）+ 帖内按 like_count DESC, create_time ASC, comment_id ASC 排序取页'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '根评论热评排序投影（post_comment 的可见根行 1:1 索引，同事务直写）';

-- ── feed 异构索引 + 浏览 UV 三表（feed-index-uv 票 01：主页 feed 排序查询从 post 直查切换的落点；
--    主键/时间列例外口径被市集六表沿用，见下节）──
-- 主键口径例外（对上文「第一列恒为 id」的显式偏离，按 map 定稿）：三表均为投影/明细/加速列、不设独立业务列，
-- 主键直接用业务键——1:1 投影与计数表 ODKU upsert 直达（撞 PK 即翻行），明细表 PK (user_id, post_id)
-- 让二级索引叶子天然携带 post_id（feed 出 ID → 回查组装的读路径免一层回表）。
-- 时间列按各自需要收窄：post_feed_index 只留 update_time（feed_time 承担行诞生时刻），
-- user_view_history 只留 viewed_at（首刷建行、重复浏览全量刷新），post_view_count 按惯例双列收尾。

-- feed 只取 state = NORMAL 行；排序键永远不用 update_time（技术时间戳，会被浏览回写等无关写污染）。
-- 索引尾列 post_id 做游标分页：WHERE state = ? AND (feed_time, post_id) < (?, ?)，弃 offset。
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

-- UV 明细即事实源（一人一帖一行）：INSERT IGNORE 成功（affected = 1）才算一次新浏览，重复进详情只刷 viewed_at 不加数；
-- 同时是浏览历史与 feed 已读灰显的数据源，用户维度，将来按 user_id 分片友好。
CREATE TABLE IF NOT EXISTS user_view_history (
    user_id   VARCHAR(36)   NOT NULL COMMENT '浏览者用户 ID',
    post_id   VARCHAR(36)   NOT NULL COMMENT '帖子业务 ID',
    viewed_at DATETIME NOT NULL COMMENT '最近浏览时刻（合并事务内 INSERT IGNORE 后对全组键 UPDATE 刷新；浏览历史排序键）',
    PRIMARY KEY (user_id, post_id),
    KEY idx_user_viewed (user_id, viewed_at) COMMENT '浏览历史：user_id 过滤 + viewed_at 倒序游标分页'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户浏览历史（UV 明细即事实源，post_view_count 是它的加速列）';

-- 浏览 UV 加速列：精确值 = user_view_history 行数，本表不是真相源。与点赞计数的不对称：浏览没有取消语义，
-- 计数只增不减，合并事务按 INSERT IGNORE 受影响行数累加，不用 upsert 计数（affected rows 会被同秒重复刷 viewed_at 打乱）。
CREATE TABLE IF NOT EXISTS post_view_count (
    post_id     VARCHAR(36)   NOT NULL COMMENT '帖子业务 ID（唯一）',
    view_count  INT      NOT NULL DEFAULT 0 COMMENT '浏览 UV 数（= user_view_history 行数；详情读路径 selectCountsByPostId 的 view 段换源到本表）',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (post_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '帖子浏览计数表（UV 加速列，事实源在 user_view_history）';

-- ── 市集（闲置 + 跑腿）feed 异构索引 + 浏览 UV 六表（market-feed-uv 票 01：照上面帖子三表先例分域落两份）──
-- 主键口径同上文例外：六表均为投影/明细/加速列、不设独立业务列，主键直接用业务键。
-- 时间列收窄同口径：两张索引表只留 update_time（feed_time 承担行诞生时刻），两张明细表只留 viewed_at。
-- 两域不泛化成一张 target_type 表：明细表二级索引叶子天然携带业务 ID 免回表，post 侧三表一行不动。

-- 市集 feed 只取：商品 state = ON_SALE、跑腿 state = PUBLISHED（大厅前端恒传 status=PUBLISHED）。
-- 索引尾列业务 ID 做游标分页，弃 offset；时间升序用 idx_state_feed 反向扫描，不单设索引。
-- 价格进索引表是帖子没有的新排序键：商品在售改价立即生效，改价事务必须同事务旁路同步（票 03）。
CREATE TABLE IF NOT EXISTS product_feed_index (
    product_id  VARCHAR(36)   NOT NULL COMMENT '商品业务 ID（product.product_id，1:1 投影）',
    state       VARCHAR(20)   NOT NULL DEFAULT 'ON_SALE' COMMENT '商品状态快照（与 product.state 同域，状态流转事务同步；feed 只查 ON_SALE）',
    feed_time   DATETIME      NOT NULL COMMENT '进入当前对外可见轮的时刻（上架事务写入：行诞生落第一次、relist 重提刷新；其余写路径不碰；「最新」排序键）',
    create_time DATETIME      NOT NULL COMMENT '商品发布时刻快照（product.create_time，上架事务随行写入；「热度」同值收敛键：view_count 同值时按 create_time DESC 收敛成确定序——UUID 无序，不能再拿 product_id 当时间代理）',
    price       DECIMAL(10,2) NOT NULL COMMENT '价格快照（product.price；在售改价事务同事务旁路同步，否则价格排序漂移；「价格升降」排序键）',
    view_count  INT           NOT NULL DEFAULT 0 COMMENT '浏览 UV 数快照（浏览合并事务旁路 ±N；真源 product_view_count，UPDATE 落 0 行落日志交重建 job；「热度」排序键）',
    update_time DATETIME      NOT NULL COMMENT '更新时间（技术时间戳，禁止当排序键）',
    PRIMARY KEY (product_id),
    KEY idx_state_feed (state, feed_time, product_id) COMMENT '「最新」：state 过滤 + feed_time 游标分页（时间升序反向扫描）',
    KEY idx_state_view (state, view_count, create_time, product_id) COMMENT '「热度」：state 过滤 + view_count DESC, create_time DESC, product_id DESC 游标分页',
    KEY idx_state_price (state, price, product_id) COMMENT '「价格升降」：state 过滤 + price 游标分页'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品 feed 异构索引（product 的 1:1 排序投影，同事务直写）';

CREATE TABLE IF NOT EXISTS errand_feed_index (
    errand_order_id VARCHAR(36) NOT NULL COMMENT '跑腿订单业务 ID（errand_order.errand_order_id，1:1 投影）',
    state           VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED' COMMENT '跑腿状态快照（与 errand_order.state 同域，13 态流转点同步；feed 只查 PUBLISHED，行常驻其余状态不可见）',
    feed_time       DATETIME    NOT NULL COMMENT '进入大厅的时刻（支付事务写入：行诞生落第一次；跑腿无重提再上架，其余写路径不碰；「最新」排序键）',
    create_time     DATETIME    NOT NULL COMMENT '订单创建时刻快照（errand_order.create_time，支付事务随行写入；「热度」同值收敛键：view_count 同值时按 create_time DESC 收敛成确定序——UUID 无序，不能再拿 errand_order_id 当时间代理）',
    price           DECIMAL(10,2) NOT NULL COMMENT '悬赏快照（errand_order.reward；行只在支付完成时诞生、彼时悬赏必已支付冻结，故非空；「价格升降」排序键）',
    view_count      INT         NOT NULL DEFAULT 0 COMMENT '浏览 UV 数快照（浏览合并事务旁路 ±N；真源 errand_view_count，UPDATE 落 0 行落日志交重建 job；「热度」排序键）',
    update_time     DATETIME    NOT NULL COMMENT '更新时间（技术时间戳，禁止当排序键）',
    PRIMARY KEY (errand_order_id),
    KEY idx_state_feed (state, feed_time, errand_order_id) COMMENT '「最新」：state 过滤 + feed_time 游标分页（时间升序反向扫描）',
    KEY idx_state_view (state, view_count, create_time, errand_order_id) COMMENT '「热度」：state 过滤 + view_count DESC, create_time DESC, errand_order_id DESC 游标分页',
    KEY idx_state_price (state, price, errand_order_id) COMMENT '「价格升降」：state 过滤 + price 游标分页'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '跑腿 feed 异构索引（errand_order 的 1:1 排序投影，同事务直写）';

-- 市集 UV 明细即事实源（一人一对象一行）：INSERT IGNORE 成功（affected = 1）才算一次新浏览，
-- 重复进详情只刷 viewed_at 不加数；同时是市集页已读灰显的数据源。匿名不计入（商品语义变更：现状匿名也计）。
CREATE TABLE IF NOT EXISTS product_view_history (
    user_id    VARCHAR(36)   NOT NULL COMMENT '浏览者用户 ID',
    product_id VARCHAR(36)   NOT NULL COMMENT '商品业务 ID',
    viewed_at  DATETIME NOT NULL COMMENT '最近浏览时刻（合并事务内 INSERT IGNORE 后对全组键 UPDATE 刷新）',
    PRIMARY KEY (user_id, product_id),
    KEY idx_user_viewed (user_id, viewed_at) COMMENT '按人浏览明细倒序（市集页浏览痕迹/灰显）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品浏览历史（UV 明细即事实源，product_view_count 是它的加速列）';

CREATE TABLE IF NOT EXISTS errand_view_history (
    user_id        VARCHAR(36)   NOT NULL COMMENT '浏览者用户 ID',
    errand_order_id VARCHAR(36) NOT NULL COMMENT '跑腿订单业务 ID',
    viewed_at      DATETIME NOT NULL COMMENT '最近浏览时刻（合并事务内 INSERT IGNORE 后对全组键 UPDATE 刷新）',
    PRIMARY KEY (user_id, errand_order_id),
    KEY idx_user_viewed (user_id, viewed_at) COMMENT '按人浏览明细倒序（市集页浏览痕迹/灰显）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '跑腿浏览历史（UV 明细即事实源，errand_view_count 是它的加速列）';

-- 市集浏览 UV 加速列：精确值 = 各域明细表行数，本表不是真相源。浏览没有取消语义，计数只增不减，
-- 合并事务按 INSERT IGNORE 受影响行数累加，不用 upsert 计数（affected rows 会被同秒重复刷 viewed_at 打乱）。
CREATE TABLE IF NOT EXISTS product_view_count (
    product_id  VARCHAR(36)   NOT NULL COMMENT '商品业务 ID（唯一）',
    view_count  INT      NOT NULL DEFAULT 0 COMMENT '浏览 UV 数（= product_view_history 行数；商品详情读路径 view 段换源到本表）',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品浏览计数表（UV 加速列，事实源在 product_view_history）';

CREATE TABLE IF NOT EXISTS errand_view_count (
    errand_order_id VARCHAR(36) NOT NULL COMMENT '跑腿订单业务 ID（唯一）',
    view_count      INT      NOT NULL DEFAULT 0 COMMENT '浏览 UV 数（= errand_view_history 行数；跑腿详情读路径 view 段接线到本表）',
    create_time     DATETIME NOT NULL COMMENT '创建时间',
    update_time     DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (errand_order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '跑腿浏览计数表（UV 加速列，事实源在 errand_view_history）';

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

-- ── 超时任务分表 timeout_task_00..15（超时中心内嵌化票 02：原独立工程搬进本库）──
-- 16 张同构分表，业务/测试 SQL 一律写逻辑表 timeout_task，物理路由由 ShardingSphere 的 hint 强制路由
-- （app 模块 sharding.yaml + bs-infrastructure 的 TimeoutTaskShardSlots / 两个 hint 算法）。
-- 槽位 g = floorMod(hash(biz_id), 16)：hash 输入只有 biz_id——同一业务对象的多种超时/多轮次落同一张表，
-- uk_type_biz (task_type, biz_id) 的全局幂等不受影响（同键行永远同表）。槽位 g 是「库 × 表」两维表达的
-- 全局展平编号（db = g/16、table = g%16），本期 DB_COUNT = 1 且不得启用 >1：超时行落到另一个库 = 与
-- 业务写入不同事务（硬约束，见 backend/docs/architecture/timeout.md §0）。
-- 列照搬原独立库的 schema-timeout-task.sql 并删了回调路由列（进程内分发不再需要按应用名路由）；
-- 时间列保留 created_at/updated_at（不打乱本库 create_time/update_time 口径是有意的：这批表按原样
-- 从原独立库迁入，列名改名要同时动 16 份 DDL + mapper XML + 存量迁移，收益只有命名整齐）。
-- 表结构完全一致（一份 DDL × 16），逐张写出而不用存储过程/内联表达式：内联表达式生成的是
-- timeout_task_0..，没有两位零填充，与 _00..15 对不上；运维要按表名 grep / 单表 DROP 重建。
-- 标识 = 自然键 (task_type, biz_id)，无中心生成的 taskId：uk_type_biz + 路由函数确定性（同 key 永远
-- 同表）= 全局幂等；某行全部读写同库同表，无跨库事务问题。
SET NAMES utf8mb4;

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

CREATE TABLE IF NOT EXISTS timeout_task_01 (
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务分表 01';

CREATE TABLE IF NOT EXISTS timeout_task_02 (
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务分表 02';

CREATE TABLE IF NOT EXISTS timeout_task_03 (
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务分表 03';

CREATE TABLE IF NOT EXISTS timeout_task_04 (
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务分表 04';

CREATE TABLE IF NOT EXISTS timeout_task_05 (
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务分表 05';

CREATE TABLE IF NOT EXISTS timeout_task_06 (
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务分表 06';

CREATE TABLE IF NOT EXISTS timeout_task_07 (
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务分表 07';

CREATE TABLE IF NOT EXISTS timeout_task_08 (
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务分表 08';

CREATE TABLE IF NOT EXISTS timeout_task_09 (
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务分表 09';

CREATE TABLE IF NOT EXISTS timeout_task_10 (
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务分表 10';

CREATE TABLE IF NOT EXISTS timeout_task_11 (
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务分表 11';

CREATE TABLE IF NOT EXISTS timeout_task_12 (
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务分表 12';

CREATE TABLE IF NOT EXISTS timeout_task_13 (
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务分表 13';

CREATE TABLE IF NOT EXISTS timeout_task_14 (
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务分表 14';

CREATE TABLE IF NOT EXISTS timeout_task_15 (
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务分表 15';

CREATE TABLE IF NOT EXISTS room (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    room_id          VARCHAR(36)  NOT NULL COMMENT '业务 ID（对外唯一标识，UUID 字符串，应用层 insert 前由 IdGenerator 生成一次写入）',
    source           VARCHAR(20)  NULL COMMENT '会话来源（首次由市集「私信联系」入口建会话时写入、首来源钉死不覆盖；首页/主页入口建的为 NULL）：ERRAND/PRODUCT',
    creator_id     VARCHAR(36)       NULL COMMENT '会话创建人 user_id，首建落定不覆盖（对家角色标签的相对视角锚点；来源为 NULL 时也为 NULL）',
    last_seq         BIGINT       NOT NULL DEFAULT 0 COMMENT '消息序号水位（取号器）：发消息事务内 UPDATE last_seq=last_seq+1 原子取号，回滚无空洞',
    state            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT '与取号器同行：一把行锁完成取号+终态判定；ACTIVE/SEALED（封盘即宿主业务终态，禁发言禁成员变更，历史只读；当前恒 1v1，SEALED 不再产生，列随口径保留）',
    create_time      DATETIME     NOT NULL COMMENT '创建时间',
    update_time      DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_room_id (room_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话表（1v1，纯取号器 + 会话真相：预览/排序已迁用户维度 user_room，未读数由 last_seq − 水位线现算）';
-- room.user_min / room.user_max / uk_direct_pair 已迁出（配对收口：1v1 查重与配对
-- 映射独立成 user_pair_room 表，room 回归纯会话实体、不知道「配对」的存在）。
-- room.last_msg_type / last_msg_preview / last_msg_at 已迁出（排序键迁用户维度 user_room；
-- 预览去副本化后连 user_room 也不落，读时按 message 真相现算，room 回归纯取号器 + 会话真相）。
-- 存量库不迁移（uuid-biz-id：删库重建），历史割接脚本已随 uuid-biz-id 票 08 退役。

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

-- chat-member-table-refactor 票 01（双维度拆分）：原 room_member（用户维度成员状态行）改名
-- user_room（last_read_seq→read_seq、last_msg_at→last_msg_time）；
-- 会话维度名册另立新表 room_member（「这个会话有谁」，1v1 恒两行，join_seq 入群水位）。
-- 票 02（预览去副本）：user_room.last_msg_type / last_msg_preview 已迁出——预览不落副本，
-- 列表读时按 (room_id, room.last_seq) 点查 message 现算渲染，message 是预览唯一真相（撤回即时反映）。
-- 分库片键语义：user_room 贴 user_id（uk 首列前置）、room_member 名册贴 room_id（chat-shard-ready
-- 预案对齐归票 03）；存量库不迁移（uuid-biz-id：删库重建）。

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

CREATE TABLE IF NOT EXISTS message (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    msg_id        VARCHAR(36)   NOT NULL COMMENT '业务 ID（对外唯一标识，UUID 字符串，应用层 insert 前由 IdGenerator 生成一次写入）：跨表引用与对外暴露用它',
    room_id       VARCHAR(36)   NOT NULL COMMENT '会话业务键（room.room_id，UUID 字符串）',
    seq           BIGINT        NOT NULL COMMENT '消息序号（room.last_seq 取号；会话内从 1 起）',
    from_user_id  VARCHAR(36)        NOT NULL COMMENT '发送者 user_id（1v1 无系统消息，恒非空）',
    msg_type      VARCHAR(20)   NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/IMAGE/CARD 三类',
    content       VARCHAR(1024) NULL COMMENT 'TEXT=裸文本（上限 500 由代码常量收敛） / IMAGE=JSON{url,width,height} / CARD=恒空',
    client_msg_id VARCHAR(36)   NULL COMMENT '客户端幂等键（UUID）：重发复用同键',
    ref_type      VARCHAR(20)   NULL COMMENT 'CARD 引用类型 PRODUCT/POST/ERRAND（仅 CARD 非空）',
    ref_id        VARCHAR(36)   NULL COMMENT 'CARD 引用对象业务 ID（POST/PRODUCT/ERRAND 均为 UUID 字符串，票 03/05；渲染时实时查，失效显示「内容已失效」）',
    state         VARCHAR(20)   NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/RECALLED：撤回占 seq 不回退、计入未读',
    recalled_at   DATETIME      NULL COMMENT '撤回时间',
    recalled_by   VARCHAR(20)   NULL COMMENT 'USER=发送者限时窗口内自撤',
    create_time   DATETIME      NOT NULL COMMENT '创建时间',
    update_time   DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_id (msg_id) COMMENT '业务键唯一；timeline 与对外接口的引用锚点',
    UNIQUE KEY uk_room_seq (room_id, seq) COMMENT '历史分页游标 + 推送判重键',
    UNIQUE KEY uk_room_client (room_id, client_msg_id) COMMENT '发送幂等：撞键回查收敛同一条'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息内容表（内容中心，只写一份）';

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

CREATE TABLE IF NOT EXISTS user_seq (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    user_id     VARCHAR(36)   NOT NULL COMMENT '用户 ID',
    seq         BIGINT   NOT NULL DEFAULT 0 COMMENT '投递序号计数器（0 基）',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user (user_id) COMMENT '计数器取号的冲突键（ODKU 靠它命中）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '投递序号计数器（每用户一行）';

CREATE TABLE IF NOT EXISTS notification (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    notification_id  VARCHAR(36)  NOT NULL COMMENT '业务 ID（对外唯一标识，UUID 字符串；uuid-biz-id 票 06：应用层 IIdGenerator 随 insert 一次写入，物理自增 id 保留纯内部键）',
    user_id          VARCHAR(36)       NOT NULL COMMENT '接收用户',
    scope            VARCHAR(20)  NOT NULL COMMENT '收件范围（写入时由生产点显式标注，禁止从 (type,refType) 推导——同一 ORDER_EVENT×ERRAND 发用户=MARKET_ERRAND、发全站系统位=BOTTOM_SYSTEM）：MARKET_ERRAND/MARKET_PRODUCT(预留,当前无生产方)/BOTTOM_SYSTEM/INTERACTION_LIKE/INTERACTION_COMMENT',
    seq              BIGINT       NOT NULL COMMENT 'per-(user,scope) 收件序号（notification_seq 计数器写入事务内取号，从 1 起递增）：已读态 = seq ≤ 该范围水位；水位只用本列，禁用 id——分配序≠提交序',
    sender_id        VARCHAR(36)       NULL COMMENT '触发者用户 ID（互动通知：谁评/回/赞了你；广播类为空）',
    type             VARCHAR(20)  NOT NULL COMMENT '类型 ANNOUNCEMENT/ORDER_EVENT/CREDIT_CHANGE/POST_COMMENTED/COMMENT_REPLIED/POST_LIKED/COMMENT_LIKED',
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

CREATE TABLE IF NOT EXISTS notification_seq (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     VARCHAR(36)      NOT NULL COMMENT '接收用户 ID',
    scope       VARCHAR(20) NOT NULL COMMENT '收件范围（与 notification.scope 同域）',
    seq         BIGINT      NOT NULL DEFAULT 0 COMMENT '序号计数器：首次取号建行 seq=1，之后写入事务内 UPDATE seq=seq+1 读回，行锁到提交',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_scope (user_id, scope)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '通知收件序号计数器表（每用户每范围一行）';

CREATE TABLE IF NOT EXISTS notification_read (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       VARCHAR(36)      NOT NULL COMMENT '接收用户 ID',
    scope         VARCHAR(20) NOT NULL COMMENT '收件范围（每个范围各推各的水位，互不误清）',
    last_read_seq BIGINT      NOT NULL DEFAULT 0 COMMENT '已读水位线：以下（seq≤）全已读以上全未读；推水位=取当前 notification_seq.seq（dedup 幂等忽略的空号一并越过）',
    create_time   DATETIME    NOT NULL COMMENT '创建时间',
    update_time   DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_scope (user_id, scope)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '通知已读水位表（每用户每范围一行）';

CREATE TABLE IF NOT EXISTS errand_order (
    id                BIGINT         NOT NULL AUTO_INCREMENT,
    errand_order_id   VARCHAR(36)    NOT NULL COMMENT '业务 ID（对外唯一标识，UUID 字符串）',
    publisher_id      VARCHAR(36)         NOT NULL COMMENT '下单方用户 ID',
    taker_id          VARCHAR(36)         NULL COMMENT '接单方用户 ID（抢单后填充，接单方取消后清空）',
    lock_seq          INT            NOT NULL DEFAULT 0 COMMENT '锁单轮次（每次抢单 +1；超时中心复合 bizId={orderId}:{lock_seq} 的轮次与回调守卫比对值）',
    event_seq         INT            NOT NULL DEFAULT 0 COMMENT '事件序号计数器：事件表取号用——事件写入事务内 UPDATE event_seq=event_seq+1 后读回（先动订单行持行锁，同订单串行发号）；同订单内递增即事件先后顺序（业务含义只认 event_seq，自增 id 无业务含义）',
    title             VARCHAR(50)    NULL COMMENT '订单标题（草稿允许为空，提交时必填）',
    type              VARCHAR(20)    NULL COMMENT '类型 EXPRESS_PICKUP/BUY/DELIVER/OTHER（草稿允许为空，提交时必填）',
    description       VARCHAR(200)   NULL COMMENT '任务描述（同步初筛覆盖；草稿允许为空，提交时必填）',
    pickup_location   VARCHAR(100)   NULL COMMENT '取件地点（草稿允许为空，提交时必填）',
    delivery_location VARCHAR(100)   NULL COMMENT '送达地点（草稿允许为空，提交时必填）',
    reward            DECIMAL(10,2)  NULL COMMENT '悬赏（校园币，支付时冻结；草稿允许为空，提交时必填）',
    images            VARCHAR(1000)  NULL COMMENT '配图 URL 列表（JSON 数组，最多 6 张，MinIO）',
    deadline          DATETIME       NULL COMMENT '截止时间（用户指定的绝对时刻，合法区间为数据库时钟 now+15min~now+7d，SQL 侧校验；到期自动下架与接单方完工时限共用）',
    state             VARCHAR(20)    NOT NULL DEFAULT 'PENDING_PAYMENT' COMMENT '状态 DRAFT(草稿)/PENDING_PAYMENT/PUBLISHED/LOCKED(锁单中)/CONFIRMED(已确认)/PICKED_UP/DELIVERED/COMPLETED/CANCELLED/DISPUTED/DELETED(软删，仅草稿可达)（先付后发：支付完成即发布进大厅）',
    create_time       DATETIME       NOT NULL COMMENT '创建时间',
    update_time       DATETIME       NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_errand_order_id (errand_order_id),
    KEY idx_state_deadline (state, deadline),
    KEY idx_publisher (publisher_id),
    KEY idx_taker (taker_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '跑腿订单表';

CREATE TABLE IF NOT EXISTS errand_order_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    VARCHAR(36)  NOT NULL COMMENT '跑腿订单业务 ID',
    event_seq   INT          NOT NULL COMMENT '事件序号（业务顺序）：写入时从 errand_order.event_seq 计数器同事务取号，同订单内递增即状态变更顺序；id 仅内部键，无业务含义',
    event_type  VARCHAR(30)  NOT NULL COMMENT '事件类型',
    from_state  VARCHAR(20)  NULL     COMMENT '变更前状态（NULL 仅限创建事件）',
    to_state    VARCHAR(20)  NOT NULL COMMENT '变更后状态',
    actor_id    VARCHAR(36)       NULL     COMMENT '操作人用户 ID（仅 SYSTEM 允许 NULL）',
    actor_role  VARCHAR(20)  NOT NULL COMMENT '操作方 PUBLISHER/TAKER/SYSTEM/ADMIN',
    reason_type VARCHAR(50)  NULL     COMMENT '原因类型（仅取消/退出/争议/仲裁类事件写入）',
    reason      VARCHAR(200) NULL     COMMENT '原因补充说明（用户输入，提交时先过同步初筛；SYSTEM 事件恒 NULL）',
    images      VARCHAR(1000) NULL    COMMENT '随附凭证图 URL 列表（JSON 数组，MinIO，INSERT 时定型不可改）',
    request_id  VARCHAR(64)  NULL     COMMENT '触发本事件的请求编号（客户端每次点击生成、重试沿用；请求级幂等判定用，SYSTEM 事件为 NULL）',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_order_seq (order_id, event_seq),
    -- 防占单（票 05）：Redis 降级时按 actor_id + event_type + 近 24h 计主动放弃，是事实源侧唯一查询路径
    KEY idx_actor_type_time (actor_id, event_type, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '跑腿订单状态流转记录（只插入不修改；同订单内 event_seq 递增即状态变更顺序，纠纷溯源事实源）';

CREATE TABLE IF NOT EXISTS product_order (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    product_order_id       VARCHAR(36)   NOT NULL COMMENT '业务 ID（对外唯一标识，UUID 字符串）',
    buyer_id               VARCHAR(36)        NOT NULL COMMENT '买家用户 ID',
    seller_id              VARCHAR(36)        NOT NULL COMMENT '卖家用户 ID',
    product_id             VARCHAR(36)   NOT NULL COMMENT '商品业务 ID（引用：跳转/反查/对账用）',
    price                  DECIMAL(10,2) NOT NULL COMMENT '成交单价快照（订单总金额 = price × quantity）',
    quantity               INT           NOT NULL DEFAULT 1 COMMENT '成交件数（1..N，下单时原子扣减台账 remaining；回补按此列）',
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

CREATE TABLE IF NOT EXISTS product_order_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    VARCHAR(36)  NOT NULL COMMENT '商品订单业务 ID',
    event_type  VARCHAR(30)  NOT NULL COMMENT '事件类型 CREATED/PAID/DELIVERED/COMPLETED/CANCELLED',
    from_state  VARCHAR(20)  NULL     COMMENT '变更前状态（NULL 仅限创建事件）',
    to_state    VARCHAR(20)  NOT NULL COMMENT '变更后状态',
    actor_id    VARCHAR(36)       NULL     COMMENT '操作人用户 ID（仅 SYSTEM 允许 NULL）',
    actor_role  VARCHAR(20)  NOT NULL COMMENT '操作方 BUYER/SELLER/SYSTEM/ADMIN',
    reason_type VARCHAR(50)  NULL     COMMENT '原因类型（仅取消/退出/争议/仲裁类事件写入）',
    reason      VARCHAR(200) NULL     COMMENT '原因补充说明（用户输入，提交时先过同步初筛；SYSTEM 事件恒 NULL）',
    images      VARCHAR(1000) NULL    COMMENT '随附凭证图 URL 列表（JSON 数组，MinIO，INSERT 时定型不可改）',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品订单状态流转记录（只插入不修改；同订单内 id 递增即状态变更顺序，纠纷溯源事实源）';

CREATE TABLE IF NOT EXISTS virtual_wallet (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    wallet_id     VARCHAR(36)   NOT NULL COMMENT '业务 ID（对外唯一标识，UUID 字符串；uuid-biz-id 票 06：应用层 IIdGenerator 随 insert 一次写入）',
    user_id       VARCHAR(36)        NOT NULL COMMENT '用户 ID',
    balance       DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '余额',
    frozen_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '冻结金额（托管中）',
    create_time   DATETIME      NOT NULL COMMENT '创建时间',
    update_time   DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_wallet_id (wallet_id),
    UNIQUE KEY uk_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '虚拟钱包表';

CREATE TABLE IF NOT EXISTS wallet_detail (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    wallet_id   VARCHAR(36)   NOT NULL COMMENT '钱包业务 ID（引用 virtual_wallet.wallet_id，UUID 字符串）',
    user_id     VARCHAR(36)        NOT NULL COMMENT '用户 ID（钱包所有者；INCOME 为收款方）',
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

CREATE TABLE IF NOT EXISTS user_credit_detail (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     VARCHAR(36)      NOT NULL COMMENT '用户 ID',
    `change`    INT         NOT NULL COMMENT '信用分变化（可为负）',
    reason_type VARCHAR(30) NOT NULL COMMENT '原因类型 ERRAND_TAKER_CANCEL/ERRAND_TIMEOUT/TRADE_COMPLAINT/ADMIN_ADJUST/ADMIN_BAN/ADMIN_UNBAN（取值见 CreditReasonTypeVO）',
    ref_id      VARCHAR(36) NULL COMMENT '关联业务 ID（跑腿=订单；内容类=被罚内容；ADMIN 类为空，不依赖幂等键）',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_reason_ref (user_id, reason_type, ref_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '信用分流水表';

