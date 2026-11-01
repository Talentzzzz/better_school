import { httpGet, httpPost } from './http'
import type { PageResult, WalletBalance, WalletTransaction } from '@/types/api'

export function getBalance() {
  return httpGet<WalletBalance>('/wallet/balance')
}

export function recharge(amount: number) {
  return httpPost<null>('/wallet/recharge', { amount })
}

export function listTransactions(page = 1, pageSize = 20) {
  return httpGet<PageResult<WalletTransaction>>('/wallet/transactions', { page, pageSize })
}
