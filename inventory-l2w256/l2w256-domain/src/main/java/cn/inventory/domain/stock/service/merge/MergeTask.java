package cn.inventory.domain.stock.service.merge;

import cn.inventory.domain.stock.model.valobj.DeductRequestVO;
import cn.inventory.domain.stock.model.valobj.DeductResultVO;
import lombok.Getter;
import lombok.ToString;

import java.util.concurrent.CompletableFuture;

/**
 * 合并缓冲区里的一个待处理请求：请求本体 + 等待结果的 future。
 *
 * <p>比对照实现少了三态 CAS（PENDING / PROCESSING / ABANDONED）：那套是给「超时放弃」用的，
 * 而本方案承诺的是「超时也照扣」（结果未知、但一定不会「说没扣却扣了」）。
 * 少一个 {@link java.util.concurrent.atomic.AtomicReference}，每个请求就少一次堆分配、
 * 少两次 volatile 读写——单热点 20k TPS 下这是每秒 4 万次多余的内存屏障。
 *
 * <p>future 只由 flush 线程完成一次；调用方可能已经超时返回，此时 completion 是空操作，
 * 请求仍在缓冲区里照常落库（这正是 TIMEOUT_UNKNOWN 的含义）。
 */
@Getter
@ToString
final class MergeTask {

    private final DeductRequestVO request;

    private final CompletableFuture<DeductResultVO> future;

    MergeTask(DeductRequestVO request, CompletableFuture<DeductResultVO> future) {
        this.request = request;
        this.future = future;
    }
}
