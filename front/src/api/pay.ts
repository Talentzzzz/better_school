import { httpGet, httpPost } from './http'
import type { PaymentOrder } from '@/types/api'

/** 收银台查询支付单（仅付款人） */
export function getPaymentOrder(payNo: string) {
  return httpGet<PaymentOrder>(`/pay/orders/${payNo}`)
}

/** 按支付单号支付（钱包扣冻 + 回调商户） */
export function payPaymentOrder(payNo: string) {
  return httpPost<null>(`/pay/orders/${payNo}/pay`)
}
