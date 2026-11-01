/** 跑腿订单状态中文名：聚合订单页 / 「我的」页 / 详情页共用一份，免得各写一份后各自漂移 */
export const ERRAND_STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_PAYMENT: '待支付',
  PENDING_REVIEW: '人工审核中',
  PUBLISHED: '待接单',
  REJECTED: '审核未通过',
  LOCKED: '锁单中',
  CONFIRMED: '已确认',
  PICKED_UP: '已取货',
  DELIVERED: '已送达',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  DISPUTED: '争议中'
}

const ERRAND_TYPE_TEXT: Record<string, string> = {
  EXPRESS_PICKUP: '代取快递',
  BUY: '代买',
  DELIVER: '代送',
  OTHER: '其他跑腿'
}

/** 跑腿类型中文名（缺失/未知类型回退「跑腿」） */
export function errandTypeText(type: string | null | undefined): string {
  return (type && ERRAND_TYPE_TEXT[type]) || '跑腿'
}
