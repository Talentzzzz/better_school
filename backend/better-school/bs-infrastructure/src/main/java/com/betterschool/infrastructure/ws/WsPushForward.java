package com.betterschool.infrastructure.ws;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 跨实例推送转发件（pub/sub 定向频道负载，JSON 文本）：本地 miss 时，
 * 推送方实例委托持有连接的目标实例代为推送。
 * userId 用于目标实例查本机注册表，frameJson 为原样线上 JSON（订阅端拿到直接写出，
 * 免 反序列化→再序列化 一轮）。
 * 只活一跳：出生在推送方 {@link WsPushRouter} 的 forward()，消亡于订阅方
 * {@link WsPushSubscriber} 的 onMessage()——不落库/不重试/不排队，
 * 漏推由客户端 (roomId, seq) 增量补拉兜底
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WsPushForward {

    private String userId;

    private String frameJson;
}
