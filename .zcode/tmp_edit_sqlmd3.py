p='sql.md'
s=open(p,encoding='utf-8').read()
pairs=[
("社区（帖子/评论/二手）、用户、钱包、chat（会话/消息/时间线，仅 1v1）、通知、审核（人工 + 举报）、跑腿与二手托管交易、信用分、敏感词、outbox。",
 "社区（帖子/评论/二手）、用户、钱包、chat（会话/消息/时间线，仅 1v1）、通知、跑腿与二手托管交易、信用分、outbox。"),
("    role          VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色 USER/AUDITOR/ADMIN',",
 "    role          VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色 USER/ADMIN',"),
(") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评论点赞计数表（专用窄行，不与评论内容/状态写入抢锁；行诞生于审核通过事务）';",
 ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评论点赞计数表（专用窄行，不与评论内容/状态写入抢锁；行诞生于评论发布事务）';"),
("行诞生于**审核通过事务**（`INSERT IGNORE`，affected = 0 落 warn）——不变式是「行诞生于审核通过」而非「行诞生于第一次点赞」",
 "行诞生于**评论发布事务**（`INSERT IGNORE`，affected = 0 落 warn）——不变式是「行诞生于评论发布」而非「行诞生于第一次点赞」"),
("行集 = 当前可见根评论：审核通过事务 `INSERT`、根评论软删事务物理 `DEL",
 "行集 = 当前可见根评论：评论发布事务 `INSERT`、根评论软删事务物理 `DEL"),
("    price           DECIMAL(10,2) NOT NULL COMMENT '悬赏快照（errand_order.reward；行只在审核通过时诞生、彼时悬赏必已支付冻结，故非空；「价格升降」排序键）',",
 "    price           DECIMAL(10,2) NOT NULL COMMENT '悬赏快照（errand_order.reward；行在支付时诞生、彼时悬赏必已支付冻结，故非空；「价格升降」排序键）',"),
]
for old,new in pairs:
    assert old in s, old[:50]
    s=s.replace(old,new)
open(p,'w',encoding='utf-8',newline='').write(s)
print("done")
