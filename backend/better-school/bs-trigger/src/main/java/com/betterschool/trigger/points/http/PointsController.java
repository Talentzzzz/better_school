package com.betterschool.trigger.points.http;

import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;
import com.betterschool.api.points.IPointsService;
import com.betterschool.api.points.dto.PointsBalanceResponseDTO;
import com.betterschool.api.points.dto.PointsTransactionResponseDTO;
import com.betterschool.domain.points.model.valobj.PointsTransactionVO;
import com.betterschool.domain.points.service.IPointsAccountService;
import com.betterschool.trigger.http.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 积分 HTTP 入口：**只有两个只读接口、零写接口**——
 * 兑抽奖次数的写入口挂在抽奖服务上（`POST /api/lottery/exchange/draw-count`），
 * 票 13 起走进程内调用（票 C 起为 case 层三段式顺序编排，无跨域事务），不再经 Dubbo。
 */
@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointsController implements IPointsService {

    private static final int MAX_PAGE_SIZE = 50;

    private final IPointsAccountService pointsAccountService;

    @Override
    @GetMapping("/balance")
    public Response<PointsBalanceResponseDTO> getBalance() {
        PointsBalanceResponseDTO dto = new PointsBalanceResponseDTO();
        dto.setBalance(pointsAccountService.getBalance(SecurityUtils.currentUserId()));
        return Response.ok(dto);
    }

    @Override
    @GetMapping("/transactions")
    public Response<PageResult<PointsTransactionResponseDTO>> listTransactions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String userId = SecurityUtils.currentUserId();
        long total = pointsAccountService.countTransactions(userId);
        List<PointsTransactionResponseDTO> dtoList = pointsAccountService.listTransactions(userId, page, pageSize)
                .stream().map(this::toTransactionResponseDTO).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    private PointsTransactionResponseDTO toTransactionResponseDTO(PointsTransactionVO transactionVO) {
        PointsTransactionResponseDTO dto = new PointsTransactionResponseDTO();
        dto.setId(transactionVO.getId());
        dto.setDirection(transactionVO.getDirection().name());
        dto.setAmount(transactionVO.getAmount());
        dto.setReasonType(transactionVO.getReasonType().name());
        dto.setRefId(transactionVO.getRefId());
        dto.setCreatedAt(transactionVO.getCreatedAt());
        dto.setExpireAt(transactionVO.getExpireAt());
        return dto;
    }
}
