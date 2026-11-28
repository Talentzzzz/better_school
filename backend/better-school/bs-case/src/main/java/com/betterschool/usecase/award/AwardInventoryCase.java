package com.betterschool.usecase.award;

import com.betterschool.domain.award.service.IAwardInventoryService;
import com.betterschool.domain.draw.service.IDrawQueryService;
import com.betterschool.types.award.AwardInventoryTypeEnum;
import com.betterschool.types.common.AppException;
import com.betterschool.types.enums.ResponseCodeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 奖品库存维护用例（运营投放 / 改量入口，case 层编排）：直接 upsert 奖品位库存账户
 * （1 奖品位 1 账户），不存在配额池与分配链路。
 * <p>
 * 编排（顺序调用，无跨域事务，CLAUDE.md §1）：
 * <ol>
 *   <li>抽奖域校验奖品位存在（跨域只读经抽奖域查询服务）；</li>
 *   <li>奖品域写台账：{@code surplus_qty / value_qty / state} 覆盖式维护，
 *       {@code waiting_qty / issued_qty} 是既有流转进度，不被覆盖。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AwardInventoryCase {

    private final IAwardInventoryService awardInventoryService;
    private final IDrawQueryService drawQueryService;

    /**
     * 维护一个奖品库存账户。
     *
     * @param awardId    奖品位 id（= 库存账户维度）
     * @param type       账户类型（PHYSICAL / VIRTUAL，大小写不敏感）
     * @param surplusQty 可扣余量（{@code -1 = 不限量}）
     * @param valueQty   单次发放面额（仅 VIRTUAL，可空）
     * @param state      0 open / 1 exhausted / 2 closed（空 = open）
     * @return true = 已写入（新建或更新）
     */
    public boolean upsert(String awardId, String type, Long surplusQty, Long valueQty, Integer state) {
        if (awardId == null || awardId.isBlank() || type == null || type.isBlank()
                || surplusQty == null || surplusQty < -1) {
            throw new AppException(ResponseCodeEnum.INVENTORY_PARAM_ERROR.getCode(), "奖品库存参数不完整");
        }
        AwardInventoryTypeEnum awardInventoryType = AwardInventoryTypeEnum.fromCode(type.trim().toLowerCase(Locale.ROOT));
        if (awardInventoryType == null) {
            throw new AppException(ResponseCodeEnum.INVENTORY_PARAM_ERROR.getCode(),
                    "未知账户类型 type=" + type);
        }
        int normalizedState = state == null ? 0 : state;
        if (normalizedState < 0 || normalizedState > 2) {
            throw new AppException(ResponseCodeEnum.INVENTORY_PARAM_ERROR.getCode(),
                    "未知账户状态 state=" + state);
        }
        if (valueQty != null && valueQty < 0) {
            throw new AppException(ResponseCodeEnum.INVENTORY_PARAM_ERROR.getCode(),
                    "面额不能为负 valueQty=" + valueQty);
        }
        if (!drawQueryService.existsAward(awardId)) {
            throw new AppException(ResponseCodeEnum.INVENTORY_PARAM_ERROR.getCode(),
                    "奖品位不存在 awardId=" + awardId);
        }
        boolean saved = awardInventoryService.upsertInventory(
                awardId, awardInventoryType, surplusQty, valueQty, normalizedState);
        log.info("[AwardInventory] 库存维护完成 awardId={} type={} surplusQty={} valueQty={} state={} result={}",
                awardId, awardInventoryType.getCode(), surplusQty, valueQty, normalizedState, saved ? "已写入" : "未写入");
        return saved;
    }
}
