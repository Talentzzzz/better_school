package com.betterschool.marketing.types.raffle;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 对账差错状态：落 {@code draw_recon_variance.state}。
 * <p>
 * 状态机 {@code PENDING → CONFIRMED → AUTO_FIXED / RESOLVED_MANUAL / FALSE_ALARM}：
 * 对账轮本轮发现的不一致一律先 {@code PENDING}；下一轮复查同键仍在才 {@code CONFIRMED}
 * （二次核对，天然消化日切边界两侧快照不一致的假差错）；复查消失则 {@code FALSE_ALARM}。
 * {@code CONFIRMED} 后分流：正向漏结且无对账单 → 复用孤儿种单入口自动补种置 {@code AUTO_FIXED}；
 * 守恒破坏 / 反向缺失等数据破坏级 → 只告警人工，人工核对后置 {@code RESOLVED_MANUAL}。
 * 三态刻意无 FAILED / PROCESSING：对账轮纯旁路，登记与状态推进都是按唯一键的条件更新，
 * 失败以异常表达、由下一轮重跑兜住。
 */
@Getter
@AllArgsConstructor
public enum DrawReconVarianceState {

    /** 本轮新登记，等待下一轮复查确认 */
    PENDING(0, "待复查"),

    /** 下一轮复查仍在，确认真实差异，进入平账分流 */
    CONFIRMED(1, "已确认"),

    /** 复用孤儿种单入口自动补种完成（正向漏结且无对账单一类） */
    AUTO_FIXED(2, "已自动修复"),

    /** 数据破坏级差异（守恒破坏 / 反向缺失），告警人工核对后处理完成 */
    RESOLVED_MANUAL(3, "已人工处理"),

    /** 下一轮复查消失：日切边界取数时刻两侧快照不一致的假差错 */
    FALSE_ALARM(4, "假差错");

    private final int code;
    private final String desc;
}
