package com.betterschool.domain.notification.adapter.port;

import java.util.List;

/**
 * 公告广播收件人端口（跨域只读边的域内声明）：由 infrastructure 实现，只读 user 域拿全部用户 ID。
 * 广播落库在一个事务里逐用户落行，故收件人名单必须在事务内一次取全（不是逐用户反查用户域）
 */
public interface IBroadcastRecipientPort {

    /** 全部用户 ID（一次查询取全量，调用方拿到后再逐用户落通知行） */
    List<String> listAllUserIds();
}
