package com.betterschool.infrastructure.dao.errand;

import com.betterschool.domain.errand.model.valobj.ErrandFeedCursorVO;
import com.betterschool.domain.errand.model.valobj.ErrandFeedIndexItemVO;
import com.betterschool.infrastructure.dao.errand.po.ErrandFeedIndexRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 跑腿大厅 feed 异构索引 Mapper（errand_feed_index 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/ErrandFeedIndexMapper.xml
 */
@Mapper
public interface IErrandFeedIndexDao {

    /**
     * 跑腿大厅索引序扫描（market-feed-uv 票 04）：只取 PUBLISHED（行常驻 + state 快照），
     * 排序键 / 游标谓词 / 排序方向随 sort 分支，走 (state, 排序键, errand_order_id) 索引的 range scan
     * （热度档为 (state, view_count, create_time, errand_order_id)，uuid-biz-id 票 05）。
     * 部分列直映域 VO（游标锚点要排序键随行带出，热度快照供大厅展示浏览量）
     */
    List<ErrandFeedIndexItemVO> selectFeedIndex(@Param("sort") String sort,
                                                @Param("cursor") ErrandFeedCursorVO cursor,
                                                @Param("limit") int limit);

    /** 审核通过：行诞生（INSERT...SELECT 取 reward/create_time 快照；ODKU 只翻 state——跑腿无重提再过审） */
    int upsertFeedIndexOnApprove(@Param("orderId") String orderId);

    /** 状态快照同步：镜像 errand_order 的新状态（大厅可见 = PUBLISHED，读路径按快照挑状态） */
    int updateFeedIndexState(@Param("orderId") String orderId, @Param("state") String state);

    /** 浏览合并事务旁路 ±N：纯 UPDATE 不 upsert；0 行 = 索引行缺失，调用方落 warn 交重建 job */
    int updateFeedIndexViewCount(@Param("orderId") String orderId, @Param("delta") int delta);

    // ── feed 索引全量重建（market-feed-uv 票 06：兜底恢复手段，照帖子 IPostFeedIndexDao 同名先例）──
    // 各步由仓储收进一个事务（执行序：计数校准 → 清空索引 → 拼装重灌）：
    // 先算准计数窄表再抄进重灌行集，读者要么看到重建前、要么看到重建后的完整索引

    /** 清空索引表（DELETE 不用 TRUNCATE——TRUNCATE 隐式提交，会毁掉与重灌语句的原子性） */
    int clearFeedIndex();

    /** 重灌写回：仓储拼装好的行集批量 INSERT（feed_time = MAX(APPROVE) 兜底 create_time；view_count 抄计数窄表） */
    int insertFeedIndexRows(@Param("rows") List<ErrandFeedIndexRow> feedIndexRowList);
}
