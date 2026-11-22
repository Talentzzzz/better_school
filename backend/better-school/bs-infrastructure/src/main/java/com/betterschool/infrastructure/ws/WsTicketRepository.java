package com.betterschool.infrastructure.ws;

import com.betterschool.types.common.Constants;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * WS 连接票据存储实现：{@link IWsTicketRepository} 的机器本体（Redis，spec §3，包内组件——对外经 WsService 门面暴露）；
 * validateAndConsume 用 getAndDelete 原子取删——同一票据只有一次生效机会，并发握手重放必有一个失败
 */
@Component
@RequiredArgsConstructor
public class WsTicketRepository implements IWsTicketRepository {

    private final RedissonClient redissonClient;

    @Override
    public String createTicket(String userId) {
        String ticket = UUID.randomUUID().toString().replace("-", "");
        RBucket<String> bucket = redissonClient.getBucket(Constants.WS_TICKET_KEY + ticket);
        bucket.set(userId, Duration.ofSeconds(Constants.WS_TICKET_TTL_SECONDS));
        return ticket;
    }

    @Override
    public String validateAndConsume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        return redissonClient.<String>getBucket(Constants.WS_TICKET_KEY + ticket).getAndDelete();
    }
}
