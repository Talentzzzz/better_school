# 01: 表拆分与全链路改名（行为保持）

**What to build:** chat 域成员表完成双维度拆分的结构与命名收口，外部行为与现状完全等价：现 room_member 改名 user_room（`last_read_seq`→`read_seq`、`last_msg_at`→`last_msg_time`，预览三件套暂留原样），新增会话维度名册表 room_member（`room_id`/`user_id`/`join_seq BIGINT NOT NULL DEFAULT 0` 入群水位/时间对，唯一键 (room_id, user_id)，1v1 恒两行）；建会话事务补两行名册写入（user_pair_room 闸门原样保留、撞 `uk_pair` 回滚幂等回读不变）；列表拼装的「对家成员行回查」改读名册表；未读数算式、已读推进守卫、软删弹回、发送/撤回/列表行为全部不变。SQL 层零 JOIN 纪律不变。

**Blocked by:** None (can start immediately)

**Status:** resolved（编译与单测已验证，行为等价待回归窗口）

- [x] schema.sql：user_room（改名 + 两列改名，预览三件套暂留）与 room_member 名册（含 join_seq）终形落定，头注记与表注释同步，含「分库片键语义」注记（user_room 贴 user_id、名册贴 room_id）
- [x] mapper XML、chat PO（用户维度 PO 带 UserRoom 语义、名册 PO 承接 RoomMember 名）、仓储接口与实现、发送/已读/列表服务层全链路随动改名
- [x] 建会话事务写入两行名册（1v1 恒两行，join_seq=0）；对家 user_id 改由名册表回查
- [x] chat 集成测试族与夹具表名列名翻新，既有断言口径不变（行为等价判据）
- [x] 交付闸门（用户拍板：不起环境）：better-school 全模块 mvn compile + test-compile 全绿 + 不依赖 Docker 的纯单测全绿；集成测试翻新到位但不实跑，票面标注「编译与单测已验证，行为等价待回归窗口」

**落地注记（实现取舍，回归窗口重点关注）：**

- user_room 终形 uk 收口为 `uk_user_room (user_id, room_id)`（片键前置，spec DDL 决策）后，原「一条 room 级 UPDATE 刷两行」的写路径（updateMemberPreview / clearHidden / markLastMsgRecalled）全部改为「名册给定成员集、逐用户执行」——语句各自走 uk 索引，行为等价（1v1 恒两行），但若名册与 user_room 行将来漂移，逐用户写会静默漏人，回归窗口留意。
- 成员页（GET /members）从单查翻成两段查：名册给定成员集 → user_room 行批量回查拼水位线（SQL 层零 JOIN 不变）。
- HTTP/WS 契约零变化：DTO/JSON 字段名 lastMsgAt/lastReadSeq 原样保留（仅装配处从内部新名收敛），WS READ 载荷本就叫 readSeq。
- 文档面（sql.md chat 段、docs/db/chat.md、CONTEXT.md 词条）按票 03 归属未随本票动。
- 捎带：DirectRoomPairPO.java 死文件删除（此前 user_pair_room 改名批次遗留，HEAD 中已无任何引用）。
- code-review 双轴已过：§9.6 useGeneratedKeys 回写按硬性约定退役；「同人唯一会话」自连断言形态保留、随表翻到 user_room。
