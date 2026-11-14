package com.betterschool.infrastructure.dao.chat;

import com.betterschool.infrastructure.dao.chat.po.Message;
import com.betterschool.infrastructure.dao.chat.po.MessagePreview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * message 表 DAO：SQL 定义在 app 模块 resources/mybatis/mapper/MessageMapper.xml。
 * msg_id 是业务 ID（UUID 字符串，应用层 insert 前发号；跨表引用与对外暴露用它）
 */
@Mapper
public interface IMessageDao {

    int insertMessage(Message message);

    Message selectMessage(@Param("roomId") String roomId, @Param("seq") Long seq);

    /** 会话列表预览点查：只取渲染要用的四列（msg_type/content/ref_type/state），不拉整条消息 */
    MessagePreview selectMessagePreview(@Param("roomId") String roomId, @Param("seq") Long seq);

    Message selectMessageByClientMsgId(@Param("roomId") String roomId, @Param("clientMsgId") String clientMsgId);

    int recallMessage(@Param("roomId") String roomId, @Param("seq") Long seq,
                      @Param("recalledBy") String recalledBy);

    Message selectMessageById(@Param("messageId") String messageId);

    List<Message> selectMessagesBefore(@Param("roomId") String roomId, @Param("beforeSeq") Long beforeSeq,
                                         @Param("limit") int limit);

    /** 增量补拉两段查之②：按 msg_id 集批量回查消息全量 */
    List<Message> selectMessagesByIds(@Param("msgIds") List<String> msgIdList);
}
