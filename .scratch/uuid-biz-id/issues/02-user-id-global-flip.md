# 02: user_id 全局翻转（两库全链路 Long→String）

**What to build:** 用户业务 ID 成为 UUID 字符串：注册时由 IdGenerator 生成、落库 `VARCHAR(36)`、实体/PO/DTO/mapper 全链路 String，并机械翻转两个后端所有承载 `user_id` 的字段——user_id 贯穿 post/comment/chat/errand/moderation/notification/wallet/search/marketing 的每一张表与每一个调用点，这一票必须一次翻完，否则编译不过。物理自增 `id` 保留为纯内部键，删除「业务 ID 与物理自增同值回写」路径中 user 相关的部分。ES 搜索文档的 userId 同步 String 化。marketing 侧的 user_id 一并翻转（与 marketing 自有 ID 无关，那在票 07）。

**Blocked by:** 01

**Status:** resolved

- [x] user 表及两库所有含 user_id 列的表 DDL 改 `VARCHAR(36)`（schema.sql / init-dev.sql / init-test.sql 直接重写，不写割接脚本，删库重建）
- [x] 注册发号走 IdGenerator；登录/个人资料/关注/收藏/隐私等用户链路 String 贯通
- [x] 全代码库 `user_id` 相关实体、PO、DTO、mapper、repository 调用点类型翻转，编译零残留
- [x] ES 投影 userId 字段 String 化并同步写入路径
- [x] 定向测试（user 域 + 受影响域的引用点）通过；全量测试全绿
