import type { IdleCategory } from '@/types/api'

/**
 * 市集列表排序（闲置五档，market-feed-uv 票 04）：热度按浏览 UV 数降序、最新 / 最早按进入
 * 可见轮的时刻、价格升降按售价。后端五档全走索引序扫描。chips 文案与图标是市集页自己的事，
 * 见 `views/MarketView.vue` 的 MARKET_SORTS
 */
export type SortOrder = 'time' | 'time_asc' | 'hot' | 'price_asc' | 'price_desc'

/** 闲置的六类商品分类（chips 配置与文本映射同源，别再各写一份） */
export const IDLE_CATEGORIES: { value: IdleCategory; label: string }[] = [
  { value: 'DIGITAL', label: '数码' },
  { value: 'BOOKS', label: '图书' },
  { value: 'DAILY', label: '日用' },
  { value: 'SPORTS', label: '运动' },
  { value: 'TICKETS', label: '票券' },
  { value: 'OTHER', label: '其他' }
]

const CATEGORY_TEXT: Record<IdleCategory, string> = Object.fromEntries(
  IDLE_CATEGORIES.map((c) => [c.value, c.label])
) as Record<IdleCategory, string>

export function idleCategoryText(category: IdleCategory | string | null | undefined): string {
  return category ? CATEGORY_TEXT[category as IdleCategory] ?? '' : ''
}
