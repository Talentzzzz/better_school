package com.betterschool.infrastructure.dao.wallet;

import com.betterschool.infrastructure.dao.wallet.po.WalletDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 钱包流水表 Mapper（wallet_detail 单表），
 * SQL 定义在 app 模块 resources/mybatis/mapper/WalletDetailMapper.xml
 */
@Mapper
public interface IWalletDetailDao {

    /** 流水先行插入（uk(order_id, type, biz_round, user_id) 冲突返回 0 = 该操作已执行过），返回影响行数 */
    int insertIgnoreDetail(WalletDetail walletDetail);

    List<WalletDetail> selectDetails(@Param("userId") String userId,
                                       @Param("offset") int offset, @Param("limit") int limit);

    long countDetails(@Param("userId") String userId);

    /** 孤儿扫描输入（ADR-0016 两段式）：有指定类型冻结无对应类型解冻（同订单同轮次同人）且冻结已超阈值的流水，按 id 升序 */
    List<WalletDetail> selectOrphanFrozen(@Param("frozenType") String frozenType,
                                          @Param("unfrozenType") String unfrozenType,
                                          @Param("thresholdMinutes") int thresholdMinutes, @Param("limit") int limit);

    /**
     * 平台侧孤儿扫描输入：平台有入账、同订单同轮次无平台出账、且入账已超阈值的流水，按 id 升序。
     * <p>
     * 判据从「冻结 / 解冻」换成「平台入账 / 平台出账」后，扫描线不再看业务方那一行——
     * 平台有进无出即「钱还在平台手上」，是宕机窗口的唯一线索。
     */
    List<WalletDetail> selectOrphanPlatformIn(@Param("platformInType") String platformInType,
                                              @Param("platformOutType") String platformOutType,
                                              @Param("thresholdMinutes") int thresholdMinutes, @Param("limit") int limit);
}
