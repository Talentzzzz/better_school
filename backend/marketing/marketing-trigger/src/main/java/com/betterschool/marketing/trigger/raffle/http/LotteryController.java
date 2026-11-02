package com.betterschool.marketing.trigger.raffle.http;

import com.betterschool.marketing.api.lottery.ILotteryService;
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
import com.betterschool.marketing.domain.raffle.model.valobj.ActivityInfoVO;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawChanceVO;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawRecordStateVO;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawRecordVO;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawResultVO;
import com.betterschool.marketing.domain.raffle.model.valobj.PrizeCatalogVO;
import com.betterschool.marketing.domain.raffle.model.valobj.ShippingResultVO;
import com.betterschool.marketing.domain.raffle.model.valobj.UserPrizeVO;
import com.betterschool.marketing.domain.raffle.service.IRaffleDrawService;
import com.betterschool.marketing.domain.raffle.service.IRaffleQueryService;
import com.betterschool.marketing.domain.raffle.service.IRaffleShippingService;
import com.betterschool.marketing.usecase.raffle.RaffleExchangeCase;
import com.betterschool.marketing.trigger.common.http.security.SecurityUtils;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.raffle.Constants;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 抽奖 HTTP 入口（票 13 重写：draw_* 新模型读侧 + 主链路）。
 * <p>
 * 免登录的只有 {@code activity} 与 {@code prizes} 两条（访客看 banner + 奖品目录），
 * 白名单在 {@code SecurityConfig}。域类型不外溢到 HTTP 契约——VO 在这里映射成 api 模块的 DTO。
 * <p>
 * 与旧模型的两处映射差异：{@code prizeType} 取 draw_strategy_award.award_type 的
 * VIRTUAL / PHYSICAL / THANKS（旧值 POINTS 归入 VIRTUAL）；「我的奖品」{@code status}
 * 用 PENDING / EXCHANGED / SHIPPED / COMPLETED 表达四段状态（旧 PENDING / RECEIVED / CREDITED 三态）。
 */
@Slf4j
@RestController
@RequestMapping("/api/lottery")
@RequiredArgsConstructor
public class LotteryController implements ILotteryService {

    private static final int MAX_PAGE_SIZE = 50;
    /** 页码上限：`(page - 1) * pageSize` 是 int 运算，不夹住的话 page=Integer.MAX_VALUE 会溢出成负 OFFSET 报 999 */
    private static final int MAX_PAGE = 10_000;

    private final IRaffleQueryService raffleQueryService;
    private final IRaffleDrawService raffleDrawService;
    private final RaffleExchangeCase raffleExchangeCase;
    private final IRaffleShippingService raffleShippingService;

    @Override
    @GetMapping("/activity")
    public Response<LotteryActivityResponseDTO> getActivity() {
        ActivityInfoVO activity = raffleQueryService.getActivity();
        if (activity == null) {
            return Response.ok(null);
        }
        LotteryActivityResponseDTO dto = new LotteryActivityResponseDTO();
        dto.setName(activity.getName());
        dto.setStartAt(activity.getStartAt());
        dto.setEndAt(activity.getEndAt());
        dto.setStatus(activity.statusAt(LocalDateTime.now()));
        dto.setDrawCountPrice(Constants.DRAW_COUNT_EXCHANGE_PRICE);
        dto.setDrawCountValidDays(activity.getDrawCountValidDays());
        return Response.ok(dto);
    }

    @Override
    @GetMapping("/prizes")
    public Response<List<LotteryPrizeResponseDTO>> listPrizes() {
        return Response.ok(raffleQueryService.listPrizes().stream().map(this::toPrizeResponseDTO).toList());
    }

    @Override
    @GetMapping("/draw-chances")
    public Response<DrawChanceResponseDTO> getDrawChances() {
        DrawChanceVO chance = raffleQueryService.getDrawChances(SecurityUtils.currentUserId());
        return Response.ok(new DrawChanceResponseDTO(chance.getAvailable(), chance.getTodayDrawn()));
    }

    @Override
    @PostMapping("/draw")
    public Response<DrawResponseDTO> draw(@Valid @RequestBody DrawRequestDTO request) {
        DrawResultVO result = raffleDrawService.draw(SecurityUtils.currentUserId(), request.getToken());
        DrawResponseDTO dto = new DrawResponseDTO();
        dto.setRecordId(result.getRecordId());
        dto.setPrizeType(result.getAwardType());
        dto.setPrizeId(result.getAwardId());
        dto.setTier(result.getLevel());
        dto.setName(result.getAwardName());
        dto.setPointsAmount(result.getPointsAmount());
        return Response.ok(dto);
    }

    @Override
    @GetMapping("/records")
    public Response<PageResult<DrawRecordResponseDTO>> listRecords(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.min(Math.max(page, 1), MAX_PAGE);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String userId = SecurityUtils.currentUserId();
        long total = raffleQueryService.countDrawRecords(userId);
        List<DrawRecordResponseDTO> dtoList = raffleQueryService.listDrawRecords(userId, page, pageSize)
                .stream().map(this::toRecordResponseDTO).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    @Override
    @GetMapping("/my-prizes")
    public Response<PageResult<UserPrizeResponseDTO>> listMyPrizes(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.min(Math.max(page, 1), MAX_PAGE);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String userId = SecurityUtils.currentUserId();
        List<String> states = parsePrizeStates(status);
        long total = raffleQueryService.countMyPrizes(userId, states);
        List<UserPrizeResponseDTO> dtoList = raffleQueryService.listMyPrizes(userId, states, page, pageSize)
                .stream().map(this::toUserPrizeResponseDTO).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    @Override
    @PostMapping("/exchange/draw-count")
    public Response<RedeemDrawCountResponseDTO> exchangeDrawCount(
            @Valid @RequestBody RedeemDrawCountRequestDTO request) {
        // 跨域兑换边（扣分 + 入账）走 case 层顺序编排，域服务各自不知道自己以外的事
        var result = raffleExchangeCase.exchangeDrawCount(SecurityUtils.currentUserId(), request.getToken());
        return Response.ok(new RedeemDrawCountResponseDTO(result.getRedeemOrderId(), result.getDrawCount()));
    }

    @Override
    @PostMapping("/my-prizes/{id}/receive")
    public Response<ReceivePrizeResponseDTO> confirmReceived(@PathVariable String id) {
        ShippingResultVO shipping = raffleShippingService.confirmReceived(SecurityUtils.currentUserId(), id);
        return Response.ok(new ReceivePrizeResponseDTO(id, toApiStatus(shipping.getState())));
    }

    // ---------- 映射 ----------

    /** 不限量档（VIRTUAL / THANKS 且无限量库存）给 unlimited=true、两个库存字段留 null */
    private LotteryPrizeResponseDTO toPrizeResponseDTO(PrizeCatalogVO prize) {
        LotteryPrizeResponseDTO dto = new LotteryPrizeResponseDTO();
        dto.setPrizeId(prize.getAwardId());
        dto.setTier(prize.getLevel());
        dto.setName(prize.getAwardName());
        dto.setType(prize.getAwardType());
        dto.setPointsAmount(prize.getPointsAmount());
        dto.setRemaining(prize.getRemaining() == null ? null : prize.getRemaining().intValue());
        dto.setTotal(prize.getTotal() == null ? null : prize.getTotal().intValue());
        dto.setUnlimited(prize.getUnlimited());
        return dto;
    }

    private DrawRecordResponseDTO toRecordResponseDTO(DrawRecordVO record) {
        DrawRecordResponseDTO dto = new DrawRecordResponseDTO();
        dto.setRecordId(record.getRecordId());
        dto.setPrizeId(record.getAwardId());
        dto.setTier(record.getLevel());
        dto.setName(record.getAwardName());
        dto.setPrizeType(record.getAwardType());
        dto.setPointsAmount(record.getPointsAmount());
        dto.setCreatedAt(record.getCreateTime());
        return dto;
    }

    private UserPrizeResponseDTO toUserPrizeResponseDTO(UserPrizeVO userPrize) {
        UserPrizeResponseDTO dto = new UserPrizeResponseDTO();
        dto.setId(userPrize.getRecordId());
        dto.setPrizeId(userPrize.getAwardId());
        dto.setTier(userPrize.getLevel());
        dto.setName(userPrize.getAwardName());
        dto.setPrizeType(userPrize.getAwardType());
        dto.setStatus(toApiStatus(userPrize.getState()));
        dto.setCreatedAt(userPrize.getCreateTime());
        dto.setClaimedAt(userPrize.getClaimedAt());
        return dto;
    }

    /**
     * 四段状态的直接映射：单值，不传按第一段（待兑换）。
     * 取值不合法走 {@code LOTTERY_PARAM_ERROR}（info 逐条固定，具体值只进日志）。
     */
    private List<String> parsePrizeStates(String status) {
        if (status == null || status.isBlank()) {
            return List.of(DrawRecordStateVO.PENDING);
        }
        String state = switch (status) {
            case "PENDING" -> DrawRecordStateVO.PENDING;
            case "EXCHANGED" -> DrawRecordStateVO.EXCHANGED;
            case "SHIPPED" -> DrawRecordStateVO.SHIPPED;
            case "COMPLETED" -> DrawRecordStateVO.COMPLETED;
            default -> null;
        };
        if (state == null) {
            log.warn("[Lottery] 我的奖品 status 取值不合法：{}", status);
            throw new AppException(ResponseCode.LOTTERY_PARAM_ERROR);
        }
        return List.of(state);
    }

    /** 域内中文库值 → HTTP 面英文状态（前端四段 tab 用，未识别的状态归 PENDING） */
    private String toApiStatus(String state) {
        if (DrawRecordStateVO.EXCHANGED.equals(state)) {
            return "EXCHANGED";
        }
        if (DrawRecordStateVO.SHIPPED.equals(state)) {
            return "SHIPPED";
        }
        if (DrawRecordStateVO.COMPLETED.equals(state)) {
            return "COMPLETED";
        }
        return "PENDING";
    }
}
