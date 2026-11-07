package com.betterschool.infrastructure.dao.wallet;

import com.betterschool.infrastructure.dao.wallet.po.VirtualWallet;
import com.betterschool.infrastructure.dao.wallet.po.WalletDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * 虚拟钱包 Mapper：SQL 定义在 app 模块 resources/mybatis/mapper/WalletMapper.xml
 */
@Mapper
public interface IWalletDao {

    int insertIgnore(VirtualWallet wallet);

    VirtualWallet selectByUserId(@Param("userId") String userId);

    /** 冻结额度：仅当可用余额（balance - frozen）充足时生效，返回影响行数 */
    int updateFreeze(@Param("userId") String userId, @Param("amount") BigDecimal amount);

    int updateUnfreeze(@Param("userId") String userId, @Param("amount") BigDecimal amount);

    int updateIncreaseBalance(@Param("userId") String userId, @Param("amount") BigDecimal amount);

    /** 流水先行插入（uk(order_id, type, biz_round, user_id) 冲突返回 0 = 该操作已执行过），返回影响行数 */
    int insertIgnoreDetail(WalletDetail detail);

    List<WalletDetail> selectDetails(@Param("userId") String userId,
                                       @Param("offset") int offset, @Param("limit") int limit);

    long countDetails(@Param("userId") String userId);
}
