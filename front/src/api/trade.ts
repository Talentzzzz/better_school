import { httpGet, httpPost, httpDelete } from './http'
import type { SortOrder } from '@/utils/trade'
import type {
  PageResult,
  Product,
  ProductOrder,
  TradeCategory
} from '@/types/api'

export interface ProductListParams {
  category?: TradeCategory
  minPrice?: number
  maxPrice?: number
  sort?: SortOrder
  /** 续页游标（market-feed-uv 票 04）：非空则忽略 page，走 keyset 续页 */
  cursor?: string
  page?: number
  pageSize?: number
}

export function listProducts(params: ProductListParams) {
  return httpGet<PageResult<Product>>('/trade/products', params)
}

export interface ProductCreateBody {
  title: string
  description: string
  images: string[]
  price: number
  tradeLocation: string
  category: TradeCategory
}

export function createProduct(body: ProductCreateBody) {
  return httpPost<number>('/trade/products', body)
}

export function getProduct(productId: number) {
  return httpGet<Product>(`/trade/products/${productId}`)
}

/** 某用户的在售商品（作者主页「闲置」tab，免登录；仅 ON_SALE） */
export function listUserProducts(userId: number, page = 1, pageSize = 50) {
  return httpGet<PageResult<Product>>(`/trade/users/${userId}/products`, { page, pageSize })
}

export function deleteProduct(productId: number) {
  return httpDelete<null>(`/trade/products/${productId}`)
}

export function markSold(productId: number) {
  return httpPost<null>(`/trade/products/${productId}/sold`)
}

export function relistProduct(productId: number) {
  return httpPost<null>(`/trade/products/${productId}/relist`)
}

export function favoriteProduct(productId: number) {
  return httpPost<null>(`/trade/products/${productId}/favorite`)
}

export function unfavoriteProduct(productId: number) {
  return httpDelete<null>(`/trade/products/${productId}/favorite`)
}

export function listFavorites(page = 1, pageSize = 10) {
  return httpGet<PageResult<Product>>('/trade/favorites', { page, pageSize })
}

export function listMyProducts(page = 1, pageSize = 10) {
  return httpGet<PageResult<Product>>('/trade/products/mine', { page, pageSize })
}

// ---------- 商品托管订单 ----------

export function createProductOrder(productId: number) {
  return httpPost<number>('/trade/orders', null, { params: { productId } })
}

export function getProductOrder(orderId: number) {
  return httpGet<ProductOrder>(`/trade/orders/${orderId}`)
}

export function payProductOrder(orderId: number) {
  return httpPost<null>(`/trade/orders/${orderId}/pay`)
}

export function markDeliveredProduct(orderId: number) {
  return httpPost<null>(`/trade/orders/${orderId}/mark-delivered`)
}

export function confirmProductOrder(orderId: number) {
  return httpPost<null>(`/trade/orders/${orderId}/confirm`)
}

export function cancelProductOrder(orderId: number) {
  return httpPost<null>(`/trade/orders/${orderId}/cancel`)
}

export function listMyProductOrders(role: 'buy' | 'sell', page = 1, pageSize = 10) {
  return httpGet<PageResult<ProductOrder>>('/trade/orders/mine', { role, page, pageSize })
}
