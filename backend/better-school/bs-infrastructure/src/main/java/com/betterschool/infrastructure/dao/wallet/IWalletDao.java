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

    int updateIncreaseBalance(@Param("userId") String userId, @Param("amount") BigDecimal amount);
}
