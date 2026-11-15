package com.betterschool.marketing.infrastructure.raffle.adapter.port.inventory;

import com.betterschool.marketing.domain.inventory.model.valobj.DeductOutcomeVO;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.domain.inventory.service.IInventoryQuotaService;
import com.betterschool.marketing.domain.raffle.adapter.port.inventory.IInventoryDeductPort;
import com.betterschool.marketing.types.inventory.AwardInventoryType;
import com.betterschool.marketing.types.inventory.InventoryBizTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 库存域发放预扣端口的实现：委托唯一的库存配额服务（账户类型由本实现选），
 * 结果对象翻成端口自己的 {@link Outcome}（不把库存域的值对象类型漏进抽奖域）。
 * <p>
 * award-inventory 两层模型：预扣锚在奖品位 id（奖品库存账户），不再是池 inv_id。
 * 纯透传：{@code recordId} 原样交给库存域服务，并在这里填 {@code bizType} 与账户类型
 * （抽奖域不认识库存的表列 / 类型枚举）。兜底件按配置惯例恒为虚拟奖品账户
 * （{@link AwardInventoryType#VIRTUAL}，与旧模型的「兜底虚拟权益」口径一致）。
 */
@Component
@RequiredArgsConstructor
public class InventoryDeductPort implements IInventoryDeductPort {

    private final IInventoryQuotaService inventoryQuotaService;

    @Override
    public boolean preDeductVirtual(String awardId, long quantity, String recordId) {
        return inventoryQuotaService.preDeduct(AwardInventoryType.VIRTUAL, awardId, quantity,
                InventoryBizTypes.LOTTERY_DRAW, recordId);
    }

    @Override
    public Outcome preDeductPhysicalWithFallback(String awardId, String fallbackAwardId, long quantity, String recordId) {
        InventoryDeductRequestVO primary = new InventoryDeductRequestVO(AwardInventoryType.PHYSICAL, awardId,
                quantity, InventoryBizTypes.LOTTERY_DRAW, recordId);
        InventoryDeductRequestVO fallback = fallbackAwardId == null ? null
                : new InventoryDeductRequestVO(AwardInventoryType.VIRTUAL, fallbackAwardId, quantity,
                InventoryBizTypes.LOTTERY_DRAW, recordId);
        DeductOutcomeVO outcome = inventoryQuotaService.preDeductWithFallback(primary, fallback);
        return switch (outcome) {
            case DEDUCTED -> Outcome.DEDUCTED;
            case FALLBACK -> Outcome.FALLBACK;
            case FAILED -> Outcome.FAILED;
        };
    }
}
