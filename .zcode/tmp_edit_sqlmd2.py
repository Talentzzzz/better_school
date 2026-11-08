p='sql.md'
s=open(p,encoding='utf-8').read()
pairs=[
("社区（帖子/评论/二手）、用户、钱包、chat（会话/消息/时间线，仅 1v1）、通知、审核（人工 + 举报）。",
 "社区（帖子/评论/二手）、用户、钱包、chat（会话/消息/时间线，仅 1v1）、通知。"),
("    create_time DATETIME NOT NULL COMMENT '创建时间（= 审核通过时刻）',",
 "    create_time DATETIME NOT NULL COMMENT '创建时间',"),
("    create_time DATETIME NOT NULL COMMENT '创建时间（= 审核通过时刻）：点赞数同值时按它升序收敛成确定序（",
 "    create_time DATETIME NOT NULL COMMENT '创建时间（= 评论发布时刻）：点赞数同值时按它升序收敛成确定序（"),
("    feed_time       DATETIME    NOT NULL COMMENT '进入大厅的时刻（审核通过事务写入：行诞生落第一次；跑腿无重提再过审",
 "    feed_time       DATETIME    NOT NULL COMMENT '进入大厅的时刻（支付事务写入：行诞生落第一次"),
("    price           DECIMAL(10,2) NOT NULL COMMENT '悬赏快照（errand_order.reward；行只在审核通过时诞生、彼时悬赏已",
 "    price           DECIMAL(10,2) NOT NULL COMMENT '悬赏快照（errand_order.reward；行在支付时诞生、彼时悬赏已"),
("    recalled_by   VARCHAR(20)   NULL COMMENT 'USER=发送者限时窗口内自撤 / AUDITOR=举报强制撤回',",
 "    recalled_by   VARCHAR(20)   NULL COMMENT 'USER=发送者限时窗口内自撤',"),
]
for old,new in pairs:
    if old in s:
        s=s.replace(old,new)
        print("ok:", old[:40])
    else:
        print("MISS:", old[:60])
open(p,'w',encoding='utf-8',newline='').write(s)
