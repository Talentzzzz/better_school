-- 票 10 验收造数：系统群（A=53 B=54 C=57 为成员，58 已被移除留痕）
INSERT INTO room (type,name,last_seq,last_msg_type,last_msg_preview,last_msg_at,status,created_at)
VALUES ('GROUP','跑腿验收群',3,'SYSTEM','用户58 被移出群聊',NOW(),'ACTIVE',NOW());
SET @room = LAST_INSERT_ID();
INSERT INTO room_user (room_id,user_id,last_read_seq,created_at) VALUES
    (@room,53,0,NOW()),(@room,54,0,NOW()),(@room,57,0,NOW());
INSERT INTO room_user (room_id,user_id,last_read_seq,left_at,created_at) VALUES (@room,58,0,NOW(),NOW());
INSERT INTO message (room_id,seq,from_user_id,msg_type,content,status,created_at) VALUES
    (@room,1,NULL,'SYSTEM','{"event":"GROUP_CREATED","text":"群聊已创建"}','NORMAL',NOW()),
    (@room,2,NULL,'SYSTEM','{"event":"MEMBER_JOINED","text":"TestC 加入群聊"}','NORMAL',NOW()),
    (@room,3,NULL,'SYSTEM','{"event":"MEMBER_REMOVED","text":"用户58 被移出群聊"}','NORMAL',NOW());
SELECT @room AS room_id;
