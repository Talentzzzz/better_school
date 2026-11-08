package com.betterschool.marketing.infrastructure.config.adapter.repository;

import com.betterschool.marketing.domain.config.adapter.repository.IConfigRepository;
import com.betterschool.marketing.domain.config.model.entity.RebateConfigEntity;
import com.betterschool.marketing.domain.config.model.valobj.RebateTypeVO;
import com.betterschool.marketing.infrastructure.config.dao.IActionRebateConfigDao;
import com.betterschool.marketing.infrastructure.config.dao.IDrawCountConfigDao;
import com.betterschool.marketing.infrastructure.config.dao.IPointsValidityConfigDao;
import com.betterschool.marketing.infrastructure.config.dao.po.ActionRebateConfigPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 配置仓储实现：只做数据访问与 PO ⇄ 领域对象映射，业务校验在域服务里。
 * 单行配置缺行视为系统错误（种子或迁移没跑），由调用方拿到异常而不是静默用默认值。
 */
@Repository
@RequiredArgsConstructor
public class ConfigRepository implements IConfigRepository {

    private final IPointsValidityConfigDao pointsValidityConfigDao;
    private final IDrawCountConfigDao drawCountConfigDao;
    private final IActionRebateConfigDao actionRebateConfigDao;

    @Override
    public int pointsValidDays() {
        Integer validDays = pointsValidityConfigDao.selectPointsValidDays();
        if (validDays == null) {
            throw new IllegalStateException("points_validity_config 缺单行配置——种子或迁移没跑");
        }
        return validDays;
    }

    @Override
    public void updatePointsValidDays(int days) {
        if (pointsValidityConfigDao.updatePointsValidDays(days) == 0) {
            throw new IllegalStateException("points_validity_config 缺单行配置，更新无效——种子或迁移没跑");
        }
    }

    @Override
    public int drawCountValidDays() {
        Integer validDays = drawCountConfigDao.selectDrawCountValidDays();
        if (validDays == null) {
            throw new IllegalStateException("draw_count_config 缺单行配置——种子或迁移没跑");
        }
        return validDays;
    }

    @Override
    public void updateDrawCountValidDays(int days) {
        if (drawCountConfigDao.updateDrawCountValidDays(days) == 0) {
            throw new IllegalStateException("draw_count_config 缺单行配置，更新无效——种子或迁移没跑");
        }
    }

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
