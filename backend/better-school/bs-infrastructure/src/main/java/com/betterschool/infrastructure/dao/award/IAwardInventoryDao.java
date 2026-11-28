package com.betterschool.infrastructure.dao.award;

import com.betterschool.infrastructure.dao.award.po.AwardInventoryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 奖品库存台账表（award_inventory）Mapper：一表一 DAO（明细表见
 * IAwardInventoryDetailDao）。SQL 定义在 marketing-app 模块 resources/mybatis/mapper/award/AwardInventoryMapper.xml。
 * <p>
 * 抽奖的预扣 / 确认只打本表（按 {@code type} 分支两条 UPDATE）；库存由运营通过
 * {@link #upsertInventory} 直接投放 / 改量。{@code surplus_qty} -1 = 不限量：守卫放行、不减 surplus。
 */
@Mapper
public interface IAwardInventoryDao {

    // ---- 台账 ----

    /** 台账行（不限 state，state 校验由域服务做） */
    AwardInventoryPO selectByAwardId(@Param("awardId") String awardId);

    /** 台账当前余量（预扣「探一次再切」的剩余量来源）；{@code -1 = 不限量} */
    Long selectSurplus(@Param("awardId") String awardId);

    /**
     * 预扣（PHYSICAL 行）：{@code surplus → waiting}，带余量守卫——0 行 = 余量不够（调用方探一次再切）。
     * {@code -1 = 不限量}不守卫、不动 surplus。surplus 恰好扣空由 open 置 exhausted；
     * state 的 IF 放在 surplus_qty 赋值之前（MySQL 的 SET 从左到右用新值），口径 = 旧值恰好扣空。
     */
    int deductPhysical(@Param("awardId") String awardId, @Param("quantity") long quantity);

    /**
     * 预扣（VIRTUAL 行）：{@code surplus → issued}（直接发放，无 waiting），守卫口径同 {@link #deductPhysical}。
     */
    int deductVirtual(@Param("awardId") String awardId, @Param("quantity") long quantity);

    /** 兑换确认（仅 PHYSICAL）：{@code waiting_qty >= qty} 守卫下转移；0 行 = 没有足够的悬挂预扣 = 不变量破坏 */
    int transferWaitingToIssued(@Param("awardId") String awardId, @Param("quantity") long quantity);

    /**
     * 孤儿预扣回补（PHYSICAL 行）：{@code waiting → surplus} 条件转移（{@code transferWaitingToIssued}
     * 的反向，守卫形状一致）；0 行 = 已回补过或守卫不过（调用方 ERROR 留人工核对）。
     * 不限量户（surplus=-1）不动 surplus；exhausted 户随回补放回 open（closed 不自动重开）。
     */
    int restitutePhysical(@Param("awardId") String awardId, @Param("quantity") long quantity);

    /**
     * 孤儿预扣回补（VIRTUAL 行）：{@code issued → surplus} 条件回退（VIRTUAL 预扣直接进 issued，
     * 无 waiting 悬挂）；0 行语义与 {@link #restitutePhysical} 相同。
     */
    int restituteVirtual(@Param("awardId") String awardId, @Param("quantity") long quantity);

    /**
     * 直接维护奖品库存账户（运营投放 / 改量）：账户不存在则建户（1 奖品位 1 账户），
     * 已存在则覆盖 {@code type / surplus_qty / value_qty / state}；{@code waiting_qty / issued_qty}
     * 是既有流转进度，不被覆盖。
     */
    int upsertInventory(@Param("awardId") String awardId, @Param("type") String type,
                        @Param("surplusQty") Long surplusQty, @Param("valueQty") Long valueQty,
                        @Param("state") Integer state);

    // ---- 预热 / 对账 ----

    List<AwardInventoryPO> selectOpenAwardInventories();

    // ---- 奖品目录读侧（票 13） ----

    /** 按 award_id 集批量回查奖品库存账户（目录 remaining/total 的拼装源）；无匹配返回空集 */
    List<AwardInventoryPO> selectByAwardIds(@Param("awardIds") List<String> awardIds);
}
