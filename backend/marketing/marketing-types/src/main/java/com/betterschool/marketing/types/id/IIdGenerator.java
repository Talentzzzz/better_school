package com.betterschool.marketing.types.id;

/**
 * 业务 ID 统一发号入口：本工程任何业务 ID（bucket_id/activity_id/strategy_id/record_id/inv_id 等）
 * 只经此接口生成，调用方不关心发号算法。当前默认实现 {@link UuidIdGenerator}（模拟雪花的全局唯一
 * 语义），将来换真雪花只换实现不动调用方。
 * <p>
 * 契约与单体侧（bs-types 的 IIdGenerator）逐字一致；两库不共享代码（types 无内部依赖、
 * 跨 project 只走 bs-api 契约）。返回值是全局唯一的业务 ID 字符串，可直接落库（VARCHAR(36)）、
 * 对外返回、跨库追踪。
 */
public interface IIdGenerator {

    /** 生成一个全局唯一的业务 ID（36 位连字符规范形字符串） */
    String nextId();
}
