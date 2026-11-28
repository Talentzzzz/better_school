package com.betterschool.infrastructure.adapter.repository.credit;

import com.betterschool.domain.credit.adapter.repository.ICreditRepository;
import com.betterschool.domain.credit.model.entity.CreditDetailEntity;
import com.betterschool.domain.credit.model.valobj.CreditChangeResult;
import com.betterschool.infrastructure.dao.credit.IUserCreditAccountDao;
import com.betterschool.infrastructure.dao.credit.IUserCreditDao;
import com.betterschool.infrastructure.dao.credit.po.CreditDetail;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;

/**
 * 信用分仓储：直操 user_credit（信用账户：分数/封禁状态）与 user_credit_detail 流水，
 * 两表均 credit 域独占写（表所有权原则：一张表只归一个领域写）。
 * 每次变更由方法自身用事务（REQUIRED）收成一个原子用例——流水先行（INSERT IGNORE，撞 uk = 已处理过）
 * 保证重试幂等，账户行懒播种（seedIfAbsent，无行 = 默认 100/NORMAL）与分数/封禁同事务
 */
@Repository
@RequiredArgsConstructor
public class CreditRepository implements ICreditRepository {

    private static final String STATE_NORMAL = "NORMAL";
    private static final String STATE_BANNED = "BANNED";
    private static final int DEFAULT_CREDIT_SCORE = 100;

    /** 信用账户（user_credit 单表，credit 域独占写） */
    private final IUserCreditAccountDao userCreditAccountDao;
    /** 信用分流水（user_credit_detail）按表拆分的 DAO */
    private final IUserCreditDao userCreditDao;
    private final TransactionTemplate transactionTemplate;

    @Override
    public String getStatus(String userId) {
        String state = userCreditAccountDao.selectState(userId);
        return state == null ? STATE_NORMAL : state;
    }

    @Override
    public int getCreditScore(String userId) {
        Integer creditScore = userCreditAccountDao.selectCreditScore(userId);
        return creditScore == null ? DEFAULT_CREDIT_SCORE : creditScore;
    }

    @Override
    public void updateCreditScore(String userId, int delta) {
        userCreditAccountDao.updateCreditScore(userId, delta);
    }

    @Override
    public boolean insertCreditDetail(CreditDetailEntity creditDetailEntity) {
        return userCreditDao.insertIgnoreCreditDetail(toPO(creditDetailEntity)) == 1;
    }

    @Override
    public int sumTodayChange(String userId, String reasonType, LocalDate today) {
        Integer sum = userCreditDao.sumTodayChange(userId, reasonType, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        return sum == null ? 0 : sum;
    }

    @Override
    public boolean banIfLowScore(String userId) {
        return userCreditAccountDao.banIfLowScore(userId) == 1;
    }

    @Override
    public List<CreditDetailEntity> pageList(String userId, int offset, int limit) {
        return userCreditDao.selectPage(userId, offset, limit).stream().map(this::toEntity).toList();
    }

    @Override
    public long countDetails(String userId) {
        return userCreditDao.countDetails(userId);
    }

    @Override
    public boolean updateStatus(String userId, String status) {
        return userCreditAccountDao.updateStatus(userId, status) == 1;
    }

    // ---------- 原子用例：仓储方法即一次事务（REQUIRED，与既有事务合流） ----------

    @Override
    public CreditChangeResult applyChangeWithDetail(CreditDetailEntity creditDetailEntity) {
        return transactionTemplate.execute(status -> {
            // 流水先行：uk_user_reason_ref 冲突 = 该变动已处理过（重试），幂等跳过
            if (!insertCreditDetail(creditDetailEntity)) {
                return null;
            }
            // 账户行懒播种：无行 = 默认 100/NORMAL，播种后再做分数/封禁变更
            userCreditAccountDao.seedIfAbsent(creditDetailEntity.getUserId());
            updateCreditScore(creditDetailEntity.getUserId(), creditDetailEntity.getChange());
            boolean justBanned = banIfLowScore(creditDetailEntity.getUserId());
            return new CreditChangeResult(getCreditScore(creditDetailEntity.getUserId()), justBanned);
        });
    }

    @Override
    public CreditChangeResult adjustByAdminWithDetail(CreditDetailEntity creditDetailEntity) {
        return transactionTemplate.execute(status -> {
            // ADMIN_ADJUST 的 ref_id 为空，不受 uk 约束（人工操作不要求幂等，前端防重）
            int delta = creditDetailEntity.getChange();
            insertCreditDetail(creditDetailEntity);
            userCreditAccountDao.seedIfAbsent(creditDetailEntity.getUserId());
            updateCreditScore(creditDetailEntity.getUserId(), delta);
            boolean justBanned = delta < 0 && banIfLowScore(creditDetailEntity.getUserId());
            return new CreditChangeResult(getCreditScore(creditDetailEntity.getUserId()), justBanned);
        });
    }

    @Override
    public void banByAdminWithDetail(CreditDetailEntity creditDetailEntity) {
        transactionTemplate.execute(status -> {
            userCreditAccountDao.seedIfAbsent(creditDetailEntity.getUserId());
            if (!updateStatus(creditDetailEntity.getUserId(), STATE_BANNED)) {
                // 条件 UPDATE 未生效：已处于目标状态 = 幂等成功；否则状态冲突报错
                if (STATE_BANNED.equals(getStatus(creditDetailEntity.getUserId()))) {
                    return null;
                }
                throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "用户状态已变化，请刷新重试");
            }
            insertCreditDetail(creditDetailEntity);
            return null;
        });
    }

    @Override
    public void unbanByAdminWithDetail(CreditDetailEntity creditDetailEntity) {
        transactionTemplate.execute(status -> {
            userCreditAccountDao.seedIfAbsent(creditDetailEntity.getUserId());
            if (!updateStatus(creditDetailEntity.getUserId(), STATE_NORMAL)) {
                if (STATE_NORMAL.equals(getStatus(creditDetailEntity.getUserId()))) {
                    return null;
                }
                throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "用户状态已变化，请刷新重试");
            }
            insertCreditDetail(creditDetailEntity);
            return null;
        });
    }

    private CreditDetail toPO(CreditDetailEntity creditDetailEntity) {
        CreditDetail creditDetail = new CreditDetail();
        creditDetail.setUserId(creditDetailEntity.getUserId());
        creditDetail.setChange(creditDetailEntity.getChange());
        creditDetail.setReasonType(creditDetailEntity.getReasonType());
        creditDetail.setRefId(creditDetailEntity.getRefId());
        return creditDetail;
    }

    private CreditDetailEntity toEntity(CreditDetail creditDetail) {
        CreditDetailEntity creditDetailEntity = new CreditDetailEntity();
        creditDetailEntity.setId(creditDetail.getId());
        creditDetailEntity.setUserId(creditDetail.getUserId());
        creditDetailEntity.setChange(creditDetail.getChange());
        creditDetailEntity.setReasonType(creditDetail.getReasonType());
        creditDetailEntity.setRefId(creditDetail.getRefId());
        creditDetailEntity.setCreateTime(creditDetail.getCreateTime());
        return creditDetailEntity;
    }
}
