package com.betterschool.domain.credit.service;

import com.betterschool.domain.credit.model.entity.CreditDetailEntity;
import com.betterschool.domain.credit.model.valobj.CreditChangeResult;
import com.betterschool.domain.credit.model.valobj.CreditReasonTypeVO;

import java.util.List;

/**
 * 信用分规则引擎（命名避免与 api.ICreditService 契约重名）。
 * 域内只做分数/流水/封禁联动，变动通知由 case 层编排（domain 不依赖其他域）
 */
public interface ICreditAccountService {

    /** 发布/接单前置校验：封禁 403 / 信用分 <60 拒绝 */
    void ensureOperable(String userId);

    /** 规则引擎统一入口（事务参与者，调用方须包事务）：扣/加分 + 流水 + 低分联动封禁，返回变动结果 */
    CreditChangeResult applyChange(String userId, CreditReasonTypeVO reasonType, Long refId);

    String getStatus(String userId);

    int getCreditScore(String userId);

    List<CreditDetailEntity> listDetails(String userId, int page, int pageSize);

    long countDetails(String userId);

    /** admin 调分（±1~±50，负分联动封禁，事务参与者） */
    CreditChangeResult adjustByAdmin(String targetUserId, int delta, String reason);

    /** admin 封禁（落 change=0 流水审计，事务参与者） */
    void banByAdmin(String targetUserId);

    /** admin 解封（事务参与者） */
    void unbanByAdmin(String targetUserId);
}
