import { httpGet, httpPost, httpDelete } from './http'
import type { SortOrder } from '@/utils/idle'
import type {
  PageResult,
  Product,
  ProductOrder,
  IdleCategory
} from '@/types/api'

export interface ProductListParams {
  category?: IdleCategory
  minPrice?: number
  maxPrice?: number
  sort?: SortOrder
  /** 续页游标（market-feed-uv 票 04）：非空则忽略 page，走 keyset 续页 */
  cursor?: string
  page?: number
  pageSize?: number
}

export function listProducts(params: ProductListParams) {
  return httpGet<PageResult<Product>>('/idle/products', params)
}

export interface ProductCreateBody {
  title: string
  description: string
  images: string[]
  price: number
  tradeLocation: string
  category: IdleCategory
}

export function createProduct(body: ProductCreateBody) {
  return httpPost<string>('/idle/products', body)
}

export function getProduct(productId: string) {
  return httpGet<Product>(`/idle/products/${productId}`)
}

/** 某用户的在售商品（作者主页「闲置」tab，免登录；仅 ON_SALE） */
export function listUserProducts(userId: string, page = 1, pageSize = 50) {
  return httpGet<PageResult<Product>>(`/idle/users/${userId}/products`, { page, pageSize })
}

export function deleteProduct(productId: string) {
  return httpDelete<null>(`/idle/products/${productId}`)
}

export function markSold(productId: string) {
  return httpPost<null>(`/idle/products/${productId}/sold`)
}

export function relistProduct(productId: string) {
  return httpPost<null>(`/idle/products/${productId}/relist`)
}

export function favoriteProduct(productId: string) {
  return httpPost<null>(`/idle/products/${productId}/favorite`)
}

export function unfavoriteProduct(productId: string) {
  return httpDelete<null>(`/idle/products/${productId}/favorite`)
}

export function listFavorites(page = 1, pageSize = 10) {
  return httpGet<PageResult<Product>>('/idle/favorites', { page, pageSize })
}

export function listMyProducts(page = 1, pageSize = 10) {
  return httpGet<PageResult<Product>>('/idle/products/mine', { page, pageSize })
}

// ---------- 商品托管订单 ----------

export function createProductOrder(productId: string) {
  return httpPost<string>('/idle/orders', null, { params: { productId } })
}

export function getProductOrder(orderId: string) {
  return httpGet<ProductOrder>(`/idle/orders/${orderId}`)
}

export function markDeliveredProduct(orderId: string) {
  return httpPost<null>(`/idle/orders/${orderId}/mark-delivered`)
}

export function confirmProductOrder(orderId: string) {
  return httpPost<null>(`/idle/orders/${orderId}/confirm`)
}

export function cancelProductOrder(orderId: string) {
  return httpPost<null>(`/idle/orders/${orderId}/cancel`)
}

export function listMyProductOrders(role: 'buy' | 'sell', page = 1, pageSize = 10) {
  return httpGet<PageResult<ProductOrder>>('/idle/orders/mine', { role, page, pageSize })
}
