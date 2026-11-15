package com.betterschool.marketing.infrastructure.inventory.dao;

import com.betterschool.marketing.infrastructure.inventory.dao.po.QuotaAllocateFlowPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 配额池分配流水表（inventory_allocation_flow）Mapper：池侧转出流水（分配的池侧幂等裁决 +
 * 池 surplus 对账依据），一表一 DAO。SQL 定义在 marketing-app 模块
 * resources/mybatis/mapper/inventory/QuotaAllocateFlowMapper.xml。
 */
@Mapper
public interface IQuotaAllocateFlowDao {

    /**
     * {@code INSERT IGNORE} 分配流水：撞 {@code uk_alloc_biz}（分配单号）静默跳过，
     * 返回 1 = 新增 / 0 = 本单已执行过（调用方按成功回放，不再动池与奖品库存）。
     */
    int insertIgnoreAllocateFlow(QuotaAllocateFlowPO flowPO);

    /** 分配单号是否已落过流水（幂等预检用） */
    boolean existsAllocateFlow(@Param("allocBizId") String allocBizId);
}
