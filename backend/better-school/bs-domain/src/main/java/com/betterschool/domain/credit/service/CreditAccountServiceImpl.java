package com.betterschool.domain.credit.service;

import com.betterschool.domain.credit.adapter.repository.ICreditRepository;
import com.betterschool.domain.credit.model.entity.CreditDetailEntity;
import com.betterschool.domain.credit.model.valobj.CreditChangeResult;
import com.betterschool.domain.credit.model.valobj.CreditReasonTypeEnum;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 信用分规则引擎：applyChange 是唯一变更入口，每次变更是一次仓储原子用例（分数 + 流水 + 封禁联动）；
 * 流水先行插入（uk_user_reason_ref 幂等）——已处理过的重试直接跳过返回 null，
 * 跨域一致性靠"幂等 + 重试"（case 层顺序调用，失败由调用方重试）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditAccountServiceImpl implements ICreditAccountService {

    private static final int MIN_OPERABLE_SCORE = 60;

    private final ICreditRepository creditRepository;

    @Override
    public void ensureOperable(String userId) {
        // state 判断优先：BANNED 即使分数回升也禁止操作，防止钻空子
        if ("BANNED".equals(creditRepository.getStatus(userId))) {
            throw new BusinessException(ResponseCodeEnum.FORBIDDEN, "账号已被封禁，无法发布或接单");
        }
        if (creditRepository.getCreditScore(userId) < MIN_OPERABLE_SCORE) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "信用分低于 60，无法发布或接单");
        }
    }

    @Override
    public CreditChangeResult applyChange(String userId, CreditReasonTypeEnum creditReasonTypeVO, String refId) {
        if (creditReasonTypeVO.hasDailyCap()) {
            int todaySum = creditRepository.sumTodayChange(userId, creditReasonTypeVO.name(), LocalDate.now());
            if (todaySum + creditReasonTypeVO.getDelta() > creditReasonTypeVO.getDailyCap()) {
                log.info("[Credit] 今日加分已达上限，跳过 userId={} reasonType={}", userId, creditReasonTypeVO.name());
                return null;
            }
        }
        return creditRepository.applyChangeWithDetail(
                CreditDetailEntity.create(userId, creditReasonTypeVO.getDelta(), creditReasonTypeVO.name(), refId));
    }

    @Override
    public String getStatus(String userId) {
        return creditRepository.getStatus(userId);
    }

    @Override
    public int getCreditScore(String userId) {
        return creditRepository.getCreditScore(userId);
    }

    @Override
    public List<CreditDetailEntity> listDetails(String userId, int page, int pageSize) {
        return creditRepository.pageList(userId, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countDetails(String userId) {
        return creditRepository.countDetails(userId);
    }

    @Override
    public CreditChangeResult adjustByAdmin(String targetUserId, int delta, String reason) {
        if (delta == 0 || Math.abs(delta) > 50) {
            throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "调分范围：±1 ~ ±50");
        }
        return creditRepository.adjustByAdminWithDetail(
                CreditDetailEntity.create(targetUserId, delta, CreditReasonTypeEnum.ADMIN_ADJUST.name(), null));
    }

    @Override
    public void banByAdmin(String targetUserId) {
        creditRepository.banByAdminWithDetail(
                CreditDetailEntity.create(targetUserId, 0, CreditReasonTypeEnum.ADMIN_BAN.name(), null));
    }

    @Override
    public void unbanByAdmin(String targetUserId) {
        creditRepository.unbanByAdminWithDetail(
                CreditDetailEntity.create(targetUserId, 0, CreditReasonTypeEnum.ADMIN_UNBAN.name(), null));
    }
}
