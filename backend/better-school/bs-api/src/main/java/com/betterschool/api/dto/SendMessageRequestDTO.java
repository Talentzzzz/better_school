package com.betterschool.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发送消息请求：clientMsgId 客户端生成 UUID，乐观上屏重发复用同键实现幂等
 */
@Data
public class SendMessageRequestDTO {

    @NotBlank(message = "clientMsgId 不能为空")
    @Size(max = 36, message = "clientMsgId 最长 36 字符")
    private String clientMsgId;

    /** TEXT/IMAGE/CARD */
    @NotBlank(message = "msgType 不能为空")
    private String msgType;

    /** TEXT=裸文本 / IMAGE=JSON{url,width,height} / CARD 恒空 */
    private String content;

    /** CARD 引用类型：PRODUCT/POST/ERRAND */
    private String refType;

    private String refId;
}
