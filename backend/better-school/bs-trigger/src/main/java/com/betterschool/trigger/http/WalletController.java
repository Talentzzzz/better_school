package com.betterschool.trigger.http;

import com.betterschool.api.IWalletService;
import com.betterschool.api.dto.WalletRechargeRequestDTO;
import com.betterschool.api.dto.WalletResponseDTO;
import com.betterschool.api.dto.WalletTransactionResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;
import com.betterschool.domain.wallet.model.entity.VirtualWalletEntity;
import com.betterschool.domain.wallet.model.entity.WalletDetailEntity;
import com.betterschool.domain.wallet.service.IWalletAccountService;
import com.betterschool.trigger.http.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 虚拟钱包 HTTP 入口：全部需登录（跑腿资金托管底座）
 */
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController implements IWalletService {

    private static final int MAX_PAGE_SIZE = 50;

    private final IWalletAccountService walletAccountService;

    @Override
    @PostMapping("/recharge")
    public Response<Void> recharge(@Valid @RequestBody WalletRechargeRequestDTO request) {
        walletAccountService.recharge(SecurityUtils.currentUserId(), request.getAmount());
        return Response.ok(null);
    }

    @Override
    @GetMapping("/balance")
    public Response<WalletResponseDTO> getBalance() {
        VirtualWalletEntity walletEntity = walletAccountService.getBalance(SecurityUtils.currentUserId());
        WalletResponseDTO dto = new WalletResponseDTO();
        if (walletEntity != null) {
            dto.setBalance(walletEntity.getBalance());
            dto.setFrozenAmount(walletEntity.getFrozenAmount());
        }
        return Response.ok(dto);
    }

    @Override
    @GetMapping("/transactions")
    public Response<PageResult<WalletTransactionResponseDTO>> listTransactions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String userId = SecurityUtils.currentUserId();
        long total = walletAccountService.countDetails(userId);
        List<WalletDetailEntity> transactionList = walletAccountService.listDetails(userId, page, pageSize);
        List<WalletTransactionResponseDTO> dtoList = transactionList.stream().map(this::toTransactionResponseDTO).toList();
        return Response.ok(new PageResult<>(page, pageSize, total, dtoList));
    }

    private WalletTransactionResponseDTO toTransactionResponseDTO(WalletDetailEntity detailEntity) {
        WalletTransactionResponseDTO dto = new WalletTransactionResponseDTO();
        dto.setId(detailEntity.getId());
        dto.setType(detailEntity.getType().name());
        dto.setAmount(detailEntity.getAmount());
        dto.setOrderId(detailEntity.getOrderId());
        dto.setCreatedAt(detailEntity.getCreateTime());
        return dto;
    }
}
