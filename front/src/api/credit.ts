import { httpGet } from './http'
import type { PageResult, CreditInfo, CreditLog } from '@/types/api'

export function getMyCredit() {
  return httpGet<CreditInfo>('/credit/my')
}

export function listMyCreditLogs(page = 1, pageSize = 20) {
  return httpGet<PageResult<CreditLog>>('/credit/my/logs', { page, pageSize })
}
