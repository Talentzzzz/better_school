package com.betterschool.usecase.marketing;

import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 批量补偿的逐单隔离执行器：单笔失败不中断本轮批次，异常交给失败回调（记日志 / 退避重试 / 耗尽置死信）。
 * <p>
 * 为什么 try/catch 在这里而不是补偿的编排方法里：两条补偿链（购买单、孤儿预扣）都跨 draw / points /
 * award 多个域，按 ADR-0016 跨域编排只能在 case 层，但「一笔失败不掀翻整轮扫描」的隔离语义不属于
 * 任何单个域；把这个固定套路收进一个执行器，编排方法只描述业务步骤与失败处置。
 */
@Component
public class BatchCompensation {

    /**
     * 执行一笔补偿。
     *
     * @return true = 执行成功；false = 已交给失败回调，调用方按未收敛处理（不向外抛）
     */
    public boolean runOne(Runnable action, Consumer<Exception> onFailure) {
        try {
            action.run();
            return true;
        } catch (Exception e) {
            onFailure.accept(e);
            return false;
        }
    }
}
