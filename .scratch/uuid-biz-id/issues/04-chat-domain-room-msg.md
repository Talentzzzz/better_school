# 04: 会话域翻转（room_id / msg_id + 分片口径）

**What to build:** 会话与消息业务 ID 变 UUID 字符串：建会话、发消息、timeline 扩散、断线补拉、撤回、举报经 timeline 定位消息、推送判重全链路 String 贯通。删除 `bindRoomBizId` 类「room_id 与物理自增同值回写」路径，room_id/msg_id 由应用层在 insert 前生成。direct_room_pair 撞键回读幂等语义不变（撞唯一键回读既有 room_id 字符串）。分库预案与自检代码中「片键哈希」口径改为字符串哈希。client_msg_id（客户端幂等键）本就是 UUID，不受影响。

**Blocked by:** 02

**Status:** resolved

- [x] room/room_member/direct_room_pair/message/timeline 表 DDL 业务键改 `VARCHAR(36)`，schema 脚本重写、删库重建
- [x] 删除业务 ID 与物理自增同值回写的发号路径，改 IdGenerator 应用层发号
- [x] 建会话幂等、发送幂等、补拉游标、弹回、撤回刷新预览等行为零变化（chat 集成用例全绿）
- [x] 举报 MESSAGE 经 msg_id 定位消息正文的链路 String 贯通
- [x] 分片相关自检/预案文档中片键哈希口径同步为字符串哈希
- [x] 定向测试通过；全量测试全绿（全量项按用户拍板豁免，见下注）

> **resolved 注记（2026-11-01）**：定向回归 chat 六类 36/36 全绿 + moderation Review* 七类 33/33 + NotificationReadModel 4/4 全绿（含 chat 集成用例：建会话幂等/发送幂等两时序/补拉游标/弹回/撤回三件套与预览替换/举报强撤/WS 判重合流）。全量套件两轮均被 Docker 栈基础设施打断（bs-mysql 连续两次 mysqld 崩溃 Exit(2)，第一轮崩溃于 trade 段、第二轮复跑同因；连接池 total=0 成片 Communications link failure，非代码回归），按用户拍板「不用测了，直接提交」豁免重跑。遗留注记已登记：票 06 补「片键哈希=字符串哈希」口径（分库预案文档 chat-shard-ready 06 尚未编写、现有 ShardingSelfCheck 无 chat 片键哈希，无存量代码可改）；票 08 登记 sql.md chat 五表段旧口径与 front 硬编码（`Number(route.params.roomId)` + `/chat/:roomId(\d+)` 数字路由正则）待 front 适配批次。UUID 形态断言按 review 意见收敛为 CommunityApiTestBase.assertUuidId 单点。
