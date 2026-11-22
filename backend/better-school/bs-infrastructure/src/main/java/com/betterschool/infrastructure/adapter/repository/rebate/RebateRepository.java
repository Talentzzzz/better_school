package com.betterschool.infrastructure.adapter.repository.rebate;

import com.betterschool.domain.rebate.adapter.repository.IRebateRepository;
import com.betterschool.domain.rebate.model.entity.RebateConfigEntity;
import com.betterschool.domain.rebate.model.valobj.RebateTypeVO;
import com.betterschool.infrastructure.dao.rebate.IActionRebateConfigDao;
import com.betterschool.infrastructure.dao.rebate.po.ActionRebateConfigPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 返利仓储实现：只做数据访问与 PO ⇄ 领域对象映射，业务校验在域服务里。
 * 一表一 DAO（action_rebate_config）；有效期两张单行表不在这里——它们归 points / raffle 各自的仓储。
 */
@Repository
@RequiredArgsConstructor
public class RebateRepository implements IRebateRepository {

    private final IActionRebateConfigDao actionRebateConfigDao;

    @Override
    public Integer findOpenRebateValue(String actionType, RebateTypeVO rebateType) {
        return actionRebateConfigDao.selectOpenRebateValue(actionType, rebateType.name());
    }

    @Override
    public List<RebateConfigEntity> listRebates() {
        return actionRebateConfigDao.selectRebates().stream().map(this::toEntity).toList();
    }

    @Override
    public RebateConfigEntity upsertRebate(RebateConfigEntity rebate) {
        ActionRebateConfigPO rebatePO = new ActionRebateConfigPO();
        rebatePO.setActionType(rebate.getActionType());
        rebatePO.setRebateType(rebate.getRebateType().name());
        rebatePO.setRebateName(rebate.getRebateName());
        rebatePO.setRebateDesc(rebate.getRebateDesc());
        rebatePO.setRebateValue(rebate.getRebateValue());
        rebatePO.setConfigId(rebate.getConfigId());
        rebatePO.setState(rebate.getState());
        actionRebateConfigDao.upsertRebate(rebatePO);
        return toEntity(actionRebateConfigDao.selectRebate(rebate.getActionType(), rebate.getRebateType().name()));
    }

    private RebateConfigEntity toEntity(ActionRebateConfigPO rebatePO) {
        RebateConfigEntity rebate = new RebateConfigEntity();
        rebate.setId(rebatePO.getId());
        rebate.setActionType(rebatePO.getActionType());
        rebate.setRebateType(RebateTypeVO.valueOf(rebatePO.getRebateType()));
        rebate.setRebateName(rebatePO.getRebateName());
        rebate.setRebateDesc(rebatePO.getRebateDesc());
        rebate.setRebateValue(rebatePO.getRebateValue());
        rebate.setConfigId(rebatePO.getConfigId());
        rebate.setState(rebatePO.getState());
        rebate.setCreateTime(rebatePO.getCreateTime());
        rebate.setUpdateTime(rebatePO.getUpdateTime());
        return rebate;
    }
}
