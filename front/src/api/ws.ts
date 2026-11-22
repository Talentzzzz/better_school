import { httpPost } from './http'

/** WS 连接一次性票据（60s 有效）：先取票再连 GET /ws?ticket= */
export function createWsTicket() {
  return httpPost<{ ticket: string }>('/ws-ticket')
}
