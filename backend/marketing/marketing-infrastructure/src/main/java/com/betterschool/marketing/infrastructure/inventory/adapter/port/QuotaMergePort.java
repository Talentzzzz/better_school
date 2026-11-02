package com.betterschool.marketing.infrastructure.inventory.adapter.port;

import com.betterschool.marketing.domain.inventory.adapter.port.IQuotaMergePort;
import com.betterschool.marketing.domain.inventory.model.valobj.ClaimItem;
import com.betterschool.marketing.domain.inventory.model.valobj.ClaimOutcome;
import com.betterschool.marketing.domain.inventory.model.valobj.DeductResult;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.infrastructure.inventory.merge.QuotaMergeBuffer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 库存进程内合并器端口的实现：纯透传给合并器实例（{@code QuotaMergeBuffer}）。
 * 域层不认识合并器类型（CLAUDE.md §1：domain 只注入本域仓储与本域 port），
 * 分片键 {@code invId} 由调用方显式给——合并器不解析请求体。
 * 预扣与确认是两个重载（spec §2.2）：共用同一实例与分片，两个失败域在合并器内就分开。
 */
@Component
@RequiredArgsConstructor
public class QuotaMergePort implements IQuotaMergePort {

    private final QuotaMergeBuffer quotaMergeBuffer;

    @Override
    public DeductResult submitAndWait(String invId, InventoryDeductRequestVO request) {
        return quotaMergeBuffer.submitAndWait(invId, request);
    }

    @Override
    public ClaimOutcome submitAndWait(String invId, ClaimItem item) {
        return quotaMergeBuffer.submitAndWait(invId, item);
    }
}
