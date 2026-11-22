package com.betterschool.marketing.api.lottery;

import com.betterschool.marketing.api.lottery.dto.DrawChanceResponseDTO;
import com.betterschool.marketing.api.lottery.dto.DrawRecordResponseDTO;
import com.betterschool.marketing.api.lottery.dto.DrawRequestDTO;
import com.betterschool.marketing.api.lottery.dto.DrawResponseDTO;
import com.betterschool.marketing.api.lottery.dto.LotteryActivityResponseDTO;
import com.betterschool.marketing.api.lottery.dto.LotteryPrizeResponseDTO;
import com.betterschool.marketing.api.lottery.dto.ReceivePrizeResponseDTO;
import com.betterschool.marketing.api.lottery.dto.RedeemDrawCountRequestDTO;
import com.betterschool.marketing.api.lottery.dto.RedeemDrawCountResponseDTO;
import com.betterschool.marketing.api.lottery.dto.UserPrizeResponseDTO;
import com.betterschool.marketing.api.common.response.PageResult;
import com.betterschool.marketing.api.common.response.Response;

import java.util.List;

/**
 * 抽奖服务对外 HTTP 契约（票 13 起落 draw_* 新模型）。
 * <p>
 * 免登录白名单只有两条：{@code getActivity} 与 {@code listPrizes}（访客看 banner + 奖品目录），
 * 其余都要登录。
 * <p>
 * 相对旧模型的接口变更（票 14 前端对齐用）：
 * <ul>
 *   <li>删除 {@code claimPrize}（实物领取：收件信息与状态推进归票 14 的发货闭环）</li>
 *   <li>删除 {@code exchangePrize}（积分兑奖品：新模型无此业务，奖品只来自抽奖）</li>
 *   <li>删除 {@code openBucket}（旧奖池桶模型整体作废；库存改走票 12 的台账 + 预热 job）</li>
 *   <li>{@code prizeType} 取值 VIRTUAL / PHYSICAL / THANKS（旧 POINTS 档归入 VIRTUAL）</li>
 * </ul>
 */
public interface ILotteryService {

    /** 活动信息（免登录）：status 由后端按 state / start_at / end_at 现算；无活动数据时 data 为 null */
    Response<LotteryActivityResponseDTO> getActivity();

    /** 奖品目录（免登录、不分页）：只暴露台账口径的 remaining 与 total，不暴露桶与在途预扣 */
    Response<List<LotteryPrizeResponseDTO>> listPrizes();

    /** 我的抽奖次数：available = 未过期桶 SUM(remain)，todayDrawn = 今日已抽 */
    Response<DrawChanceResponseDTO> getDrawChances();

    /** 单抽（幂等：同一 token 重放返回同一结果，不重复抽也不重复扣次数） */
    Response<DrawResponseDTO> draw(DrawRequestDTO request);

    /** 我的中奖记录：过滤掉谢谢参与，created_at DESC */
    Response<PageResult<DrawRecordResponseDTO>> listRecords(int page, int pageSize);

    /**
     * 我的奖品（= 中奖记录中非谢谢参与的行）：{@code status} 是四段状态的直接映射，
     * {@code created_at DESC}。**不看活动起止**——已中奖是既得权益。
     */
    Response<PageResult<UserPrizeResponseDTO>> listMyPrizes(String status, int page, int pageSize);

    /**
     * **用积分购买抽奖次数**（抽奖只消耗次数，从不直接扣积分；积分不够时走这条换次数）：
     * 扣分与次数入账两步各自域内原子、共用 token 确定性映射出的购买单号做幂等，
     * 返回购买单 id、本次花费与买后余额。份数不传按 1 份。
     * <p>
     * 路径：{@code POST /api/lottery/purchase/draw-count}（历史名 {@code /exchange/draw-count} 仍可用）。
     */
    Response<RedeemDrawCountResponseDTO> exchangeDrawCount(RedeemDrawCountRequestDTO request);

    /**
     * 确认收货（票 14 发货闭环）：实物奖 {@code 已发货 → 已完成}，同事务落 {@code COMPLETE} 动作明细。
     * 幂等：已完成的记录重复调用直接返回 COMPLETED；记录不存在 / 非本人 / 状态不是已发货
     * 一律 {@code PRIZE_NOT_RECEIVABLE(4314)}。
     */
    Response<ReceivePrizeResponseDTO> confirmReceived(String id);
}
