import { httpDelete, httpGet, httpPost, httpPut } from './http'
import type { SortOrder } from '@/utils/trade'
import type {
  PageResult,
  ErrandOrder,
  ErrandType,
  ErrandStatus
} from '@/types/api'

export interface ErrandListParams {
  type?: ErrandType
  status?: ErrandStatus
  sort?: SortOrder
  /** 续页游标（market-feed-uv 票 04）：非空则忽略 page，走 keyset 续页 */
  cursor?: string
  page?: number
  pageSize?: number
}

export function listErrandOrders(params: ErrandListParams) {
  return httpGet<PageResult<ErrandOrder>>('/errand/orders', params)
}

export interface ErrandCreateBody {
  title: string
  type: ErrandType
  description: string
  pickupLocation: string
  deliveryLocation: string
  reward: number
  /** 截止时间（ISO 绝对时刻；到期自动下架与接单方完工时限共用） */
  deadline: string
  images: string[]
}

export function createErrandOrder(body: ErrandCreateBody) {
  return httpPost<number>('/errand/orders', body)
}

/** 跑腿草稿保存请求：全字段可缺省（草稿可留空），提交时才校验必填 */
export interface ErrandDraftBody {
  title?: string
  type?: ErrandType
  description?: string
  pickupLocation?: string
  deliveryLocation?: string
  reward?: number
  /** 截止时间（可缺省，提交时必填） */
  deadline?: string
  images?: string[]
}

/** 保存跑腿草稿（可全空），返回 orderId；草稿不付款不对外、不起支付计时 */
export function saveErrandDraft(body: ErrandDraftBody) {
  return httpPost<number>('/errand/orders/draft', body)
}

/** 编辑草稿（仅 DRAFT 可改，全量覆盖内容字段） */
export function updateErrandDraft(orderId: number, body: ErrandDraftBody) {
  return httpPut<null>(`/errand/orders/${orderId}/draft`, body)
}

/** 提交草稿：必填校验，缺项则停留草稿 */
export function submitErrandDraft(orderId: number) {
  return httpPost<null>(`/errand/orders/${orderId}/submit`)
}

/** 删除草稿（软删 DELETED，仅作者、仅 DRAFT） */
export function deleteErrandOrder(orderId: number) {
  return httpDelete<null>(`/errand/orders/${orderId}`)
}

export function getErrandOrder(orderId: number) {
  return httpGet<ErrandOrder>(`/errand/orders/${orderId}`)
}

export function payErrandOrder(orderId: number) {
  return httpPost<null>(`/errand/orders/${orderId}/pay`)
}

export function grabErrandOrder(orderId: number) {
  return httpPost<null>(`/errand/orders/${orderId}/grab`)
}

/** 确认接单（锁单→已确认，仅持单人）：抢到手后核对信息再点 */
export function acceptErrandOrder(orderId: number) {
  return httpPost<null>(`/errand/orders/${orderId}/accept`)
}

/** 放弃（锁单→已发布，仅持单人）：设计内的正常动作，不扣信用分 */
export function abandonErrandOrder(orderId: number) {
  return httpPost<null>(`/errand/orders/${orderId}/abandon`)
}

export function pickupErrandOrder(orderId: number) {
  return httpPost<null>(`/errand/orders/${orderId}/pickup`)
}

export function deliverErrandOrder(orderId: number) {
  return httpPost<null>(`/errand/orders/${orderId}/deliver`)
}

export function confirmErrandOrder(orderId: number) {
  return httpPost<null>(`/errand/orders/${orderId}/confirm`)
}

export function cancelErrandOrder(orderId: number) {
  return httpPost<null>(`/errand/orders/${orderId}/cancel`)
}

export function disputeErrandOrder(orderId: number) {
  return httpPost<null>(`/errand/orders/${orderId}/dispute`)
}

export function listMyErrandOrders(role: 'publish' | 'take', params: { status?: ErrandStatus; page?: number; pageSize?: number } = {}) {
  return httpGet<PageResult<ErrandOrder>>('/errand/orders/mine', { role, ...params })
}

/** 某用户发布的进行中跑腿单（作者主页「跑腿」tab，免登录；只列发布不含接单） */
export function listUserErrands(userId: number, page = 1, pageSize = 50) {
  return httpGet<PageResult<ErrandOrder>>(`/errand/users/${userId}/orders`, { page, pageSize })
}
