import { httpGet } from './http'
import type { PageResult, PostSummary, Product, ErrandOrder, UserSummary } from '@/types/api'

// 站内搜索：ES 全文检索，免登录
export function searchPosts(keyword: string, page = 1, pageSize = 20) {
  return httpGet<PageResult<PostSummary>>('/search/posts', { keyword, page, pageSize })
}

export function searchProducts(keyword: string, page = 1, pageSize = 20) {
  return httpGet<PageResult<Product>>('/search/products', { keyword, page, pageSize })
}

export function searchErrands(keyword: string, page = 1, pageSize = 20) {
  return httpGet<PageResult<ErrandOrder>>('/search/errands', { keyword, page, pageSize })
}

export function searchUsers(keyword: string, page = 1, pageSize = 20) {
  return httpGet<PageResult<UserSummary>>('/search/users', { keyword, page, pageSize })
}
