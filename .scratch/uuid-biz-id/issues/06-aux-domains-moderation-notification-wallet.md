# 06: 辅助域翻转（moderation / notification / wallet/credit 自有 ID）

**What to build:** 审核举报域（report_id、审核任务/复审等自有业务 id）、通知域（notification_id、广播 msg_id——广播键已是 UUID 字符串，落库列与实体类型对齐）、钱包/积分明细域（wallet_id、credit 明细引用）自有业务 ID 变 UUID 字符串。这些域的用户身份引用已在票 02 翻完，本票只翻各域自有业务键；纯物理主键（无业务含义的 id）不动。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] moderation/notification/wallet/credit 相关表 DDL 自有业务键改 `VARCHAR(36)`，schema 脚本重写、删库重建
- [ ] 各域发号走 IdGenerator，应用层随 insert 一次写入
- [ ] 举报（POST/COMMENT/MESSAGE/MERCHANT）→ 审核队列 → 强制撤回/驳回全链路 String 贯通
- [ ] 通知下发、广播幂等、已读未读 String 贯通
- [ ] 定向测试通过；全量测试全绿
