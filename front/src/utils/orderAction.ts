import type { ProductOrderStatus } from '@/types/api'

/** 当前用户相对该订单的角色 */
export type OrderRole = 'buyer' | 'seller' | 'visitor'

export interface OrderAction {
  key: 'pay' | 'cancel' | 'delivered' | 'confirm'
  /** 只写动词，金额由金额格承担 */
  label: string
  danger?: boolean
}

export interface OrderActionSet {
  /** 状态说明，任何组合都展示 */
  note: string
  /** 金额格标签；缺省则本组合不渲染金额格（金额取订单 price） */
  amountLabel?: string
  /** 至多一个主操作 */
  primary?: OrderAction
  /** 取消恒定排在主操作左侧 */
  secondary?: OrderAction
  /** 无可点操作时的等待文案；终态不填——说明已讲完，再补一行是重复 */
  waiting?: string
}

/**
 * 状态文案必须按角色取词——卖家点进待支付单不该看到买家口吻的「待支付」。
 * PENDING_PAYMENT 的「支付」、DELIVERED 的「收货」都只是买家视角；对卖家/访客讲的是「谁在等谁」。
 */
export function statusText(status: ProductOrderStatus, role: OrderRole): string {
  switch (status) {
    case 'PENDING_PAYMENT':
      return role === 'buyer' ? '待支付' : '待买家付款'
    case 'PAID':
      return role === 'seller' ? '待交付' : '待卖家交付'
    case 'DELIVERED':
      if (role === 'buyer') return '待收货'
      return role === 'seller' ? '待买家确认' : '待买家收货'
    case 'COMPLETED':
      return '已完成'
    case 'CANCELLED':
      return '已取消'
  }
}

/** 说明同样按角色分：催付款／催确认只对买家说；对卖家说「能拿到什么」。 */
export function statusNote(status: ProductOrderStatus, role: OrderRole): string {
  switch (status) {
    case 'PENDING_PAYMENT':
      return role === 'buyer'
        ? '15 分钟内未支付将自动取消，商品恢复在售'
        : '买家付款前订单可随时取消'
    case 'PAID':
      return role === 'buyer' ? '货款已托管冻结，交付前买家可取消订单' : '货款已托管冻结，交付后转给你'
    case 'DELIVERED':
      return role === 'seller' ? '买家确认收货后，货款转给你' : '买家确认收货后，货款转给卖家'
    case 'COMPLETED':
      return role === 'seller' ? '交易完成，货款已转给你' : '交易完成，货款已转给卖家'
    case 'CANCELLED':
      return '订单已关闭，商品已恢复在售'
  }
}

const CANCEL: OrderAction = { key: 'cancel', label: '取消订单', danger: true }
const PAY: OrderAction = { key: 'pay', label: '确认支付' }
const DELIVERED: OrderAction = { key: 'delivered', label: '标记已交付' }
const CONFIRM: OrderAction = { key: 'confirm', label: '确认收货' }

/**
 * 「状态 × 角色」→ 操作集合。判据 `primary || secondary` 决定页面走哪一支：
 * 有可点操作 → 状态条 + 等分底栏；纯等待／终态 → 状态卡、不设底栏。
 */
export function orderActions(status: ProductOrderStatus, role: OrderRole): OrderActionSet {
  const note = statusNote(status, role)

  switch (status) {
    case 'PENDING_PAYMENT':
      if (role === 'buyer') return { note, amountLabel: '应付金额', secondary: CANCEL, primary: PAY }
      if (role === 'seller') return { note, amountLabel: '预计收款', waiting: '等待买家付款' }
      return { note, amountLabel: '订单金额', waiting: '等待买家付款' }

    case 'PAID':
      if (role === 'buyer') return { note, amountLabel: '货款（托管中）', secondary: CANCEL }
      if (role === 'seller') return { note, amountLabel: '货款（托管中）', primary: DELIVERED }
      return { note, amountLabel: '货款（托管中）', waiting: '等待卖家交付' }

    case 'DELIVERED':
      if (role === 'buyer') return { note, amountLabel: '待付货款', primary: CONFIRM }
      if (role === 'seller') return { note, amountLabel: '待收货款', waiting: '等待买家确认收货' }
      return { note, amountLabel: '订单金额', waiting: '等待买家确认收货' }

    case 'COMPLETED':
      return { note }

    case 'CANCELLED':
      return { note }
  }
}
