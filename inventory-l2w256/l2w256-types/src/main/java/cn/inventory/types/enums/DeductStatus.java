package cn.inventory.types.enums;

/**
 * 扣减结果状态（合并扣减的完整语义）
 *
 * <p><b>失败 ≠ 错</b>，这是合并扣减对外承诺里最容易做错的一件事。五个「非成功」状态分成两类：
 *
 * <table border="1">
 *   <caption>对外语义</caption>
 *   <tr><th>状态</th><th>库存动了吗</th><th>客户端该做什么</th></tr>
 *   <tr><td>{@link #TIMEOUT_UNKNOWN}</td><td><b>大概率动了</b>（请求仍在缓冲区里，稍后落库）</td>
 *       <td>不能报失败！返回「处理中」，凭 bizNo 查询或重试</td></tr>
 *   <tr><td>{@link #INSUFFICIENT}</td><td>没动</td><td>明确告诉用户库存不足</td></tr>
 *   <tr><td>{@link #REJECTED}</td><td>没动</td><td>背压拒绝，退避后重试</td></tr>
 *   <tr><td>{@link #ERROR}</td><td>没动（事务已回滚）</td><td>可重试</td></tr>
 *   <tr><td>{@link #DUPLICATE}</td><td>本次没动（此前已扣）</td><td>幂等命中，按成功处理</td></tr>
 * </table>
 *
 * <p>把 {@link #TIMEOUT_UNKNOWN} 当成失败返回，用户会重复下单——这是生产事故，不是显示问题。
 */
public enum DeductStatus {

    /** 扣减成功（本次事务里真的扣了） */
    SUCCESS,

    /** 幂等命中：同 bizNo 此前已扣成功，本次不重复扣 */
    DUPLICATE,

    /** 库存不足：事务回滚，库存与流水都没动 */
    INSUFFICIENT,

    /**
     * 等待超时，结果未知：请求还在分片缓冲区里，之后大概率仍会落库。
     * <p>对外必须表达为「处理中」，<b>不能说失败</b>。
     */
    TIMEOUT_UNKNOWN,

    /** 分片缓冲区已满，入口背压拒绝：请求从未进入缓冲区，库存一定没动 */
    REJECTED,

    /** 执行异常（落库异常 / 线程中断）：事务已回滚，可重试 */
    ERROR
}
