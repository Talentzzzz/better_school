package com.betterschool.infrastructure.dao.errand;

import com.betterschool.infrastructure.dao.errand.po.ErrandOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 跑腿浏览 UV 计数窄表 Mapper（errand_view_count 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/ErrandViewCountMapper.xml
 */
@Mapper
public interface IErrandViewCountDao {

    /** 浏览 UV 计数窄表批量读：按 id 集单表 IN 查询，仓储补 0 拼装（详情 UV 回填与列表展示共用） */
    List<ErrandOrder> selectViewCountsByOrderIds(@Param("orderIds") List<String> orderIdList);

    /** 第三步：加速列 upsert（行懒建 ODKU）；增量 = insertOrderViewHistory 的影响行数 */
    int upsertOrderViewCount(@Param("orderId") String orderId, @Param("delta") int delta);

    /** 订单当前的浏览 UV 数（加速列 errand_view_count；计数行不存在返回 null，调用方按 0 收） */
    Integer selectViewCount(@Param("orderId") String orderId);

    /** 浏览 UV 加速列校准收尾：清掉没有明细行的孤儿计数行（重投影由 {@link #recalibrateViewCounts} 完成） */
    int deleteViewCountsMissingHistory();

    /** 浏览 UV 加速列校准：errand_view_count 重投影 = errand_view_history 行数（ODKU 补缺行 / 刷漂移 / 幂等落回同值） */
    int recalibrateViewCounts();
}
