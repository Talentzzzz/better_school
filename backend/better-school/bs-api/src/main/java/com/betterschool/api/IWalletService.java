package com.betterschool.api;

import com.betterschool.api.dto.WalletRechargeRequestDTO;
import com.betterschool.api.dto.WalletResponseDTO;
import com.betterschool.api.dto.WalletTransactionResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;

/**
 * 虚拟钱包服务契约：HTTP 层由 Controller 实现（跑腿资金托管底座，v1 虚拟币 mock 充值）
 */
public interface IWalletService {

    Response<Void> recharge(WalletRechargeRequestDTO request);

    Response<WalletResponseDTO> getBalance();

    Response<PageResult<WalletTransactionResponseDTO>> listTransactions(int page, int pageSize);
}
