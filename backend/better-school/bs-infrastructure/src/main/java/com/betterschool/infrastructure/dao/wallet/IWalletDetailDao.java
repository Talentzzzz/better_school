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
    int insertIgnoreDetail(WalletDetail detail);

    List<WalletDetail> selectDetails(@Param("userId") String userId,
                                       @Param("offset") int offset, @Param("limit") int limit);

    long countDetails(@Param("userId") String userId);
}
