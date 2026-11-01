package com.betterschool.types.id;

/**
 * 业务 ID 统一发号入口：全工程任何业务 ID（user_id/post_id/room_id/msg_id 等）只经此接口生成，
 * 调用方不关心发号算法。当前默认实现 {@link UuidIdGenerator}（模拟雪花的全局唯一语义），
 * 将来换真雪花只换实现不动调用方。
 * <p>
 * 契约：返回值是全局唯一的业务 ID 字符串，可直接落库（VARCHAR(36)）、对外返回、跨库追踪。
 */
public interface IIdGenerator {

    /** 生成一个全局唯一的业务 ID（36 位连字符规范形字符串） */
    String nextId();
}
