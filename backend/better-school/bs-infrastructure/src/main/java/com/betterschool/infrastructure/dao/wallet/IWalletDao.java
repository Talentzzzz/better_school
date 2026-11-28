package com.betterschool.infrastructure.dao.wallet;

import com.betterschool.infrastructure.dao.wallet.po.VirtualWallet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

/**
 * 虚拟钱包 Mapper（virtual_wallet 单表）：流水语句见 IWalletDetailDao，
 * SQL 定义在 app 模块 resources/mybatis/mapper/WalletMapper.xml
 */
@Mapper
public interface IWalletDao {

    int insertIgnore(VirtualWallet virtualWallet);

    VirtualWallet selectByUserId(@Param("userId") String userId);

    /** 冻结额度：仅当可用余额（balance - frozen）充足时生效，返回影响行数 */
    int updateFreeze(@Param("userId") String userId, @Param("amount") BigDecimal amount);

    int updateUnfreeze(@Param("userId") String userId, @Param("amount") BigDecimal amount);

    /** 平台出账：仅当 balance 足够时扣减（平台没有冻结额语义，守卫只看余额），返回影响行数 */
    int updateDecreaseBalance(@Param("userId") String userId, @Param("amount") BigDecimal amount);

    /**
     * 买家出账：仅当可用余额（balance - frozen_amount）足够时扣减。
     * <p>
     * 为什么要减 frozen_amount：商品链路已不再写冻结额，但历史行仍可能带着冻结额，
     * 那部分钱在旧口径下已被用户「付过」，不能再被扣一次。守卫保留它作为减项，
     * 是为了让历史数据不被新口径重复消费。
     */
    int updateDecreaseAvailableBalance(@Param("userId") String userId, @Param("amount") BigDecimal amount);

    int updateIncreaseBalance(@Param("userId") String userId, @Param("amount") BigDecimal amount);
}
