package com.betterschool.trigger.draw.http;

import com.betterschool.api.lottery.ILotteryService;
import com.betterschool.api.lottery.dto.DrawChanceResponseDTO;
import com.betterschool.api.lottery.dto.DrawRecordResponseDTO;
import com.betterschool.api.lottery.dto.DrawRequestDTO;
import com.betterschool.api.lottery.dto.DrawResponseDTO;
import com.betterschool.api.lottery.dto.LotteryActivityResponseDTO;
import com.betterschool.api.lottery.dto.LotteryPrizeResponseDTO;
import com.betterschool.api.lottery.dto.ReceivePrizeResponseDTO;
import com.betterschool.api.lottery.dto.RedeemDrawCountRequestDTO;
import com.betterschool.api.lottery.dto.RedeemDrawCountResponseDTO;
import com.betterschool.api.lottery.dto.UserPrizeResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;
import com.betterschool.domain.draw.model.valobj.ActivityInfoVO;
import com.betterschool.domain.draw.model.valobj.DrawChanceVO;
import com.betterschool.domain.draw.model.valobj.DrawRecordStateVO;
import com.betterschool.domain.draw.model.valobj.DrawRecordVO;
import com.betterschool.domain.draw.model.valobj.DrawResultVO;
import com.betterschool.domain.draw.model.valobj.PrizeCatalogVO;
import com.betterschool.domain.draw.model.valobj.ShippingResultVO;
import com.betterschool.domain.draw.model.valobj.UserPrizeVO;
import com.betterschool.domain.draw.service.IDrawQueryService;
import com.betterschool.domain.draw.service.IDrawShippingService;
import com.betterschool.usecase.marketing.DrawCase;
import com.betterschool.trigger.http.security.SecurityUtils;
import com.betterschool.types.common.AppException;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.draw.Constants;
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

    private final IDrawQueryService drawQueryService;
    private final DrawCase drawCase;
    private final IDrawShippingService drawShippingService;

    @Override
    @GetMapping("/activity")
    public Response<LotteryActivityResponseDTO> getActivity() {
        ActivityInfoVO activityInfoVO = drawQueryService.getActivity();
        if (activityInfoVO == null) {
            return Response.ok(null);
        }
        LotteryActivityResponseDTO lotteryActivityResponseDTO = new LotteryActivityResponseDTO();
        lotteryActivityResponseDTO.setName(activityInfoVO.getName());
        lotteryActivityResponseDTO.setStartAt(activityInfoVO.getStartAt());
        lotteryActivityResponseDTO.setEndAt(activityInfoVO.getEndAt());
        lotteryActivityResponseDTO.setStatus(activityInfoVO.statusAt(LocalDateTime.now()));
        lotteryActivityResponseDTO.setDrawCountPrice(Constants.DRAW_COUNT_PURCHASE_PRICE);
        lotteryActivityResponseDTO.setDrawCountValidDays(activityInfoVO.getDrawCountValidDays());
        return Response.ok(lotteryActivityResponseDTO);
    }

    @Override
    @GetMapping("/prizes")
    public Response<List<LotteryPrizeResponseDTO>> listPrizes() {
        return Response.ok(drawQueryService.listPrizes().stream().map(this::toPrizeResponseDTO).toList());
    }

    @Override
    @GetMapping("/draw-chances")
    public Response<DrawChanceResponseDTO> getDrawChances() {
        DrawChanceVO drawChanceVO = drawQueryService.getDrawChances(SecurityUtils.currentUserId());
        return Response.ok(new DrawChanceResponseDTO(drawChanceVO.getAvailable(), drawChanceVO.getTodayDrawn()));
    }

    @Override
    @PostMapping("/draw")
    public Response<DrawResponseDTO> draw(@Valid @RequestBody DrawRequestDTO drawRequestDTO) {
        DrawResultVO drawResultVO = drawCase.draw(SecurityUtils.currentUserId(), drawRequestDTO.getToken());
        DrawResponseDTO drawResponseDTO = new DrawResponseDTO();
        drawResponseDTO.setRecordId(drawResultVO.getRecordId());
        drawResponseDTO.setPrizeType(drawResultVO.getAwardType());
        drawResponseDTO.setPrizeId(drawResultVO.getAwardId());
        drawResponseDTO.setTier(drawResultVO.getLevel());
        drawResponseDTO.setName(drawResultVO.getAwardName());
        drawResponseDTO.setPointsAmount(drawResultVO.getPointsAmount());
        return Response.ok(drawResponseDTO);
    }

    @Override
    @GetMapping("/records")
    public Response<PageResult<DrawRecordResponseDTO>> listRecords(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.min(Math.max(page, 1), MAX_PAGE);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String userId = SecurityUtils.currentUserId();
        long total = drawQueryService.countDrawRecords(userId);
        List<DrawRecordResponseDTO> dtoList = drawQueryService.listDrawRecords(userId, page, pageSize)
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
        long total = drawQueryService.countMyPrizes(userId, states);
        List<UserPrizeResponseDTO> dtoList = drawQueryService.listMyPrizes(userId, states, page, pageSize)
                .stream().map(this::toUserPrizeResponseDTO).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    /**
     * 用积分购买抽奖次数（抽奖域内的次数资产，与抽奖同域）：三段式顺序编排
     * （ADR-0016 票 C——落单 CREATED → 扣积分 → 入账置 DONE，每段各自仓储本地事务，
     * 「单在 ⇒ 分已扣」降级为「扣分结果未知、扫描收敛」），宕机残留由
     * drawCountPurchaseScan 定时补偿收敛；同 token 重放按 Stripe 语义返回原结果、
     * 不重复扣不重复加。编排细节见 case 层 {@code DrawCase}。
     * <p>
     * 路径保留历史的 {@code /exchange/draw-count}（前端在调），并给出语义化的
     * {@code /purchase/draw-count}——两条指向同一实现。
     */
    @Override
    @PostMapping({"/purchase/draw-count", "/exchange/draw-count"})
    public Response<RedeemDrawCountResponseDTO> exchangeDrawCount(
            @Valid @RequestBody RedeemDrawCountRequestDTO redeemDrawCountRequestDTO) {
        var result = drawCase.purchaseDrawCount(
                SecurityUtils.currentUserId(), redeemDrawCountRequestDTO.getToken(), redeemDrawCountRequestDTO.quantityOrDefault());
        return Response.ok(new RedeemDrawCountResponseDTO(
                result.getPurchaseOrderId(), result.getPointsCost(), result.getDrawCount()));
    }

    @Override
    @PostMapping("/my-prizes/{id}/receive")
    public Response<ReceivePrizeResponseDTO> confirmReceived(@PathVariable String id) {
        ShippingResultVO shippingResultVO = drawShippingService.confirmReceived(SecurityUtils.currentUserId(), id);
        return Response.ok(new ReceivePrizeResponseDTO(id, toApiStatus(shippingResultVO.getState())));
    }

    // ---------- 映射 ----------

    /** 不限量档（VIRTUAL / THANKS 且无限量库存）给 unlimited=true、两个库存字段留 null */
    private LotteryPrizeResponseDTO toPrizeResponseDTO(PrizeCatalogVO prizeCatalogVO) {
        LotteryPrizeResponseDTO lotteryPrizeResponseDTO = new LotteryPrizeResponseDTO();
        lotteryPrizeResponseDTO.setPrizeId(prizeCatalogVO.getAwardId());
        lotteryPrizeResponseDTO.setTier(prizeCatalogVO.getLevel());
        lotteryPrizeResponseDTO.setName(prizeCatalogVO.getAwardName());
        lotteryPrizeResponseDTO.setType(prizeCatalogVO.getAwardType());
        lotteryPrizeResponseDTO.setPointsAmount(prizeCatalogVO.getPointsAmount());
        lotteryPrizeResponseDTO.setRemaining(prizeCatalogVO.getRemaining() == null ? null : prizeCatalogVO.getRemaining().intValue());
        lotteryPrizeResponseDTO.setTotal(prizeCatalogVO.getTotal() == null ? null : prizeCatalogVO.getTotal().intValue());
        lotteryPrizeResponseDTO.setUnlimited(prizeCatalogVO.getUnlimited());
        return lotteryPrizeResponseDTO;
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

    private UserPrizeResponseDTO toUserPrizeResponseDTO(UserPrizeVO userPrizeVO) {
        UserPrizeResponseDTO userPrizeResponseDTO = new UserPrizeResponseDTO();
        userPrizeResponseDTO.setId(userPrizeVO.getRecordId());
        userPrizeResponseDTO.setPrizeId(userPrizeVO.getAwardId());
        userPrizeResponseDTO.setTier(userPrizeVO.getLevel());
        userPrizeResponseDTO.setName(userPrizeVO.getAwardName());
        userPrizeResponseDTO.setPrizeType(userPrizeVO.getAwardType());
        userPrizeResponseDTO.setStatus(toApiStatus(userPrizeVO.getState()));
        userPrizeResponseDTO.setCreatedAt(userPrizeVO.getCreateTime());
        userPrizeResponseDTO.setClaimedAt(userPrizeVO.getClaimedAt());
        return userPrizeResponseDTO;
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
            throw new AppException(ResponseCodeEnum.LOTTERY_PARAM_ERROR);
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
