package com.betterschool.trigger.http;

import com.betterschool.api.IPaymentService;
import com.betterschool.api.dto.PaymentOrderResponseDTO;
import com.betterschool.api.response.Response;
import com.betterschool.domain.wallet.model.entity.PaymentOrderEntity;
import com.betterschool.trigger.http.security.SecurityUtils;
import com.betterschool.usecase.payment.PaymentCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付收银台 HTTP 入口（模拟微信支付）：全部需登录，仅付款人本人可操作。
 */
@RestController
@RequestMapping("/api/pay")
@RequiredArgsConstructor
public class PaymentController implements IPaymentService {

    private final PaymentCase paymentCase;

    @Override
    @GetMapping("/orders/{payNo}")
    public Response<PaymentOrderResponseDTO> getPaymentOrder(@PathVariable String payNo) {
        PaymentOrderEntity paymentOrderEntity = paymentCase.getPaymentOrder(payNo, SecurityUtils.currentUserId());
        PaymentOrderResponseDTO paymentOrderResponseDTO = new PaymentOrderResponseDTO();
        paymentOrderResponseDTO.setPayNo(paymentOrderEntity.getPayNo());
        paymentOrderResponseDTO.setBizOrderId(paymentOrderEntity.getBizOrderId());
        paymentOrderResponseDTO.setSubject(paymentOrderEntity.getSubject());
        paymentOrderResponseDTO.setAmount(paymentOrderEntity.getAmount());
        paymentOrderResponseDTO.setState(paymentOrderEntity.getState().name());
        paymentOrderResponseDTO.setCreatedAt(paymentOrderEntity.getCreatedAt());
        return Response.ok(paymentOrderResponseDTO);
    }

    @Override
    @PostMapping("/orders/{payNo}/pay")
    public Response<Void> payOrder(@PathVariable String payNo) {
        paymentCase.pay(payNo, SecurityUtils.currentUserId());
        return Response.ok(null);
    }
}
