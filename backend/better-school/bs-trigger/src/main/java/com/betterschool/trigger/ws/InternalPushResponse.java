package com.betterschool.trigger.ws;

/**
 * 实例间内部转发应答：hit=本地确实持有该用户连接并已推出；
 * hit=false 表示明确 miss（路由过期），转发方据此 CAS 清理路由条目
 */
public record InternalPushResponse(boolean hit) {
}
