package com.betterschool.domain.raffle.service.recon;

/**
 * 抽奖库存全量对账轮（spec：每日 03:00 的 {@code drawReconScan}）：逐笔孤儿收敛之上的总量兜底。
 * 独立于逐笔链路回答「账到底平不平」——四条不变量（B1 台账守恒 / B3 预扣守恒 /
 * 正向配对 / 反向配对，公式出处为库存 flush 落库注释与 {@code docs/db/inventory.md} §0
 * 已声明的守恒判据）每天真跑一遍，差异进差错表状态机：
 * <ul>
 *   <li>轧账：四条不变量只读聚合，差异行逐条 {@code PENDING} 登记（INSERT IGNORE 幂等）；</li>
 *   <li>二次核对：复查上一轮 PENDING——本轮轧账同键仍在才 {@code CONFIRMED}（消化日切边界
 *       两侧快照不一致的假差错），消失则 {@code FALSE_ALARM}；</li>
 *   <li>平账分流（CONFIRMED 后）：正向漏结且查无对账单 → 复用
 *       {@link com.betterschool.domain.raffle.service.orphan.IRaffleOrphanPrereserveService}
 *       现有种单入口补种（不复制种单逻辑），置 {@code AUTO_FIXED}；孤儿单已 DEAD / 守恒破坏 /
 *       反向缺失 → 只告警人工（经告警端口），差错保持 CONFIRMED 等人工置 RESOLVED_MANUAL——
 *       数据破坏级绝不自动修；</li>
 *   <li>DEAD 孤儿单点名：死信是逐笔链路已确认的事实，登记即 CONFIRMED 并直接告警，
 *       周期性点名防「当次 ERROR 之后被遗忘」。</li>
 * </ul>
 * 纯旁路：只读业务表（对账产物经只读查询端口与仓储读面）、只写差错表/轮次表，
 * 与孤儿收敛 job 零代码共享（不依赖其代码路径，两层机制不共享同一个 bug）。
 */
public interface IRaffleDrawReconService {

    /**
     * 跑一轮全量对账（job 唯一入口）：起轮 → 轧账登记 → DEAD 点名 → 二次核对与分流 → 终轮回填。
     *
     * @param roundNo 轮次号，{@code yyyy-MM-dd} 日期文本（同日重跑幂等：撞 uk_round_no /
     *                uk_round_check_biz 静默跳过，终轮回填按计数覆盖无害）
     * @return 本轮处理的差错数 = 二次核对确认数 + 复查证伪数 + DEAD 点名数
     *         （新登记的 PENDING 尚未"处理"，不计入）
     */
    int scan(String roundNo);
}
