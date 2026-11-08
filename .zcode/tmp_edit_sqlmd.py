import re

P = 'sql.md'
s = open(P, encoding='utf-8').read()

def rep(old, new):
    global s
    assert old in s, old[:80]
    s = s.replace(old, new)

# 1. 表数统计
rep("全库物理表数 = **42（单体业务）+ 16（单体超时分表）+ 18（营销）= 76**。",
    "全库物理表数 = **38（单体业务）+ 16（单体超时分表）+ 18（营销）= 72**。")
rep("（17 张实体主表 + `content_review`/`review_task`/`content_report` 三张治理表，如 `bs_user.user_id`、`post.post_id`、`errand_order.errand_order_id`、`content_report.report_id`）",
    "（17 张实体主表，如 `bs_user.user_id`、`post.post_id`、`errand_order.errand_order_id`）")
rep("本文档收录 42 张业务表 + 16 张 `timeout_task` 分表。以下 6 张不在本文档范围：want_to_buy、errand_order_member、agent_review_log 已随求购/多人单下线及审核去 agent 化删除；",
    "本文档收录 38 张业务表 + 16 张 `timeout_task` 分表。以下 10 张不在本文档范围：want_to_buy、errand_order_member、agent_review_log 已随求购/多人单下线及审核去 agent 化删除；sensitive_word、content_review、review_task、content_report 已随审核功能整体下线删除；")

# 2. post 表
rep("""    state         VARCHAR(20)   NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT '状态 NORMAL/PENDING_REVIEW/REJECTED/DELETED/DRAFT（先审后发：发布落库即待审，人工通过转 NORMAL，拒绝 REJECTED，撤回/被拒/未发布进 DRAFT；待审 24h 超时提醒由本表 review_entered_at 驱动轮询扫描）',
    review_round  INT           NOT NULL DEFAULT 1 COMMENT '审核轮次：累计进入待审次数（直接发布=1，草稿创建=0、提交进待审时 +1；content_review.review_round 取此列）',
    review_entered_at DATETIME  NULL COMMENT '最近一次进入待审时间（24h 待审超时提醒扫描用；裁决/撤回不清空，下次进入待审由提交语句刷新）',
    expire_at     DATETIME      NULL COMMENT '过期归档时间（满 30 天下架，XXL-Job 后置）',""",
"""    state         VARCHAR(20)   NOT NULL DEFAULT 'NORMAL' COMMENT '状态 NORMAL/DRAFT/DELETED（发布落库即可见；未发布进 DRAFT）',
    expire_at     DATETIME      NULL COMMENT '过期归档时间（满 30 天下架，XXL-Job 后置）',""")

# 3. post_comment state
rep("""    state       VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT '状态 NORMAL/PENDING_REVIEW/REJECTED/DELETED（先审后发，存量默认 NORMAL；列表只查 NORMAL）',""",
"""    state       VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT '状态 NORMAL/DELETED（发布即可见；列表只查 NORMAL）',""")

# 4. product 表
rep("""    state          VARCHAR(20)    NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT '状态 DRAFT(草稿)/PENDING_REVIEW(待审)/ON_SALE/LOCKED(交易中)/SOLD/REJECTED(审核未通过)/DELETED（先审后售：提交进待审，审核通过转在售，拒绝转 REJECTED）',
    review_round   INT            NOT NULL DEFAULT 0 COMMENT '审核轮次：累计进入待审次数（直接发布=1，草稿提交/重新上架/在售改内容回审再 +1；content_review.review_round 取此列）',
    review_entered_at DATETIME    NULL COMMENT '最近一次进入待审时间（24h 待审超时提醒扫描用）',
    expire_at      DATETIME       NULL COMMENT '到期下架时间（进入待审时刷新满 30 天，XXL-Job 后置汇入草稿箱）',""",
"""    state          VARCHAR(20)    NOT NULL DEFAULT 'ON_SALE' COMMENT '状态 DRAFT(草稿)/ON_SALE/LOCKED(交易中)/SOLD/DELETED（发布即可见；下架/到期汇入草稿箱）',
    expire_at      DATETIME       NULL COMMENT '到期下架时间（上架时刷新满 30 天，XXL-Job 后置汇入草稿箱）',""")

# 5. notification type / scope / ref
rep("同一 ORDER_EVENT×ERRAND 发用户=MARKET_ERRAND、发审核员=BOTTOM_SYSTEM",
    "同一 ORDER_EVENT×ERRAND 发用户=MARKET_ERRAND、发全站系统位=BOTTOM_SYSTEM")
rep("'类型 ANNOUNCEMENT/AUDIT_RESULT/AUDIT_PENDING/REPORT_RESULT/ORDER_EVENT/CREDIT_CHANGE/POST_COMMENTED/COMMENT_REPLIED/POST_LIKED/COMMENT_LIKED'",
    "'类型 ANNOUNCEMENT/ORDER_EVENT/CREDIT_CHANGE/POST_COMMENTED/COMMENT_REPLIED/POST_LIKED/COMMENT_LIKED'")
rep("'关联业务 ID（POST/COMMENT/PRODUCT/ERRAND 订单均为 UUID 字符串，票 03/05；REPORT=report_id UUID，票 06）'",
    "'关联业务 ID（POST/COMMENT/PRODUCT/ERRAND 订单均为 UUID 字符串，票 03/05）'")

# 6. errand_order description/state
rep("'任务描述（同步初筛覆盖；草稿允许为空，提交时必填）'",
    "'任务描述（草稿允许为空，提交时必填）'")
rep("'状态 DRAFT(草稿)/PENDING_PAYMENT/PENDING_REVIEW(已付款待审)/PUBLISHED/LOCKED(锁单中)/CONFIRMED(已确认)/PICKED_UP/DELIVERED/COMPLETED/CANCELLED/DISPUTED/REJECTED/DELETED(软删，仅草稿可达)（先付后审：支付转待审，审核通过发布，拒绝转 REJECTED 退款）'",
    "'状态 DRAFT(草稿)/PENDING_PAYMENT/PUBLISHED/LOCKED(锁单中)/CONFIRMED(已确认)/PICKED_UP/DELIVERED/COMPLETED/CANCELLED/DISPUTED/DELETED(软删，仅草稿可达)（支付成功即 PUBLISHED 进大厅）'")

# 7. user_credit_detail reason_type
rep("'原因类型 ERRAND_TAKER_CANCEL/ERRAND_TIMEOUT/REPORT_CONFIRMED/TRADE_COMPLAINT/ADMIN_ADJUST/ADMIN_BAN/ADMIN_UNBAN（取值见 CreditReasonTypeVO）'",
    "'原因类型 ERRAND_TAKER_CANCEL/ERRAND_TIMEOUT/TRADE_COMPLAINT/ADMIN_ADJUST/ADMIN_BAN/ADMIN_UNBAN（取值见 CreditReasonTypeVO）'")
rep("'关联业务 ID（跑腿=订单；内容类=被罚内容；ADMIN 类为空，不依赖幂等键）'",
    "'关联业务 ID（跑腿=订单；ADMIN 类为空，不依赖幂等键）'")

# 8. timeline 段的举报提及
rep("timeline.msg_id 外联本列（同为 UUID 字符串）；举报（content_report.target_type=MESSAGE）按 msg_id 字符串等值定位，经 timeline 路由语义不变；分库预案中 room_id/msg_id 片键哈希一律按字符串哈希（禁数值取模假设，见 chat-shard-ready spec 头注记）。",
    "timeline.msg_id 外联本列（同为 UUID 字符串）；分库预案中 room_id/msg_id 片键哈希一律按字符串哈希（禁数值取模假设，见 chat-shard-ready spec 头注记）。")

# 9. 删四个表节（### sensitive_word 起、## 2 营销前）
m = re.search(r'### sensitive_word[\s\S]*?(?=## 2\. 营销服务)', s)
assert m
s = s[:m.start()] + s[m.end():]

open(P, 'w', encoding='utf-8', newline='').write(s)
print("sql.md done")
