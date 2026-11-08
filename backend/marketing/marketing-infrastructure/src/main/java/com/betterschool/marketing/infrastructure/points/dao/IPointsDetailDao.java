package com.betterschool.marketing.infrastructure.points.dao;

import com.betterschool.marketing.infrastructure.points.dao.po.PointsDetailPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 积分消费明细表（points_detail）Mapper：一表一 DAO。
 * SQL 定义在 marketing-app 模块 resources/mybatis/mapper/PointsDetailMapper.xml。
 */
@Mapper
public interface IPointsDetailDao {

    /** 该用户该笔业务的消费行 id（不存在返回 null）；幂等预检用，普通快照读、不加锁 */
    Long selectConsumeId(@Param("userId") String userId,
                         @Param("reasonType") String reasonType,
                         @Param("refId") String refId);

    /**
     * 一条多值 {@code INSERT IGNORE} 写下整笔消费的全部明细（一次消费一条语句，行数 = 被碰到的桶数）。
     * 撞 {@code uk_bucket_reason_ref} 的行被跳过，表现为返回行数小于传入条数——调用方据此整笔回滚。
     * <p>
     * 列表不能为空：方案至少有一条扣减（空列表会拼出 {@code VALUES} 后无值的 SQL）。
     *
     * @return 实际插入的行数
     */
    int insertIgnoreDetails(@Param("details") List<PointsDetailPO> details);
}
