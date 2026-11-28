// 营销域接口封装（points / lottery 两段，Vite 代理见 vite.config.ts）。
// 失败语义按 spec「接口口径与失败语义」：code 分支在调用方做，这里只提供 code 常量与逐条固定文案。
// 与后端的对应关系（票 14 定型；后端契约见 backend/marketing/docs/api/lottery.md）：
//   getActivity      → GET  /lottery/activity        （免登录；无活动时 data 为 null）
//   listPrizes       → GET  /lottery/prizes          （免登录；不分页）
//   getDrawChances   → GET  /lottery/draw-chances
//   draw             → POST /lottery/draw            （body { token }）
//   listMyPrizes     → GET  /lottery/my-prizes       （status 四段 + page/pageSize）
//   confirmReceived  → POST /lottery/my-prizes/{id}/receive
//   exchangeDrawCount→ POST /lottery/exchange/draw-count（body { token }）
//   getPointsBalance → GET  /points/balance
import { httpGet, httpPost } from './http'
import type { PageResult } from '@/types/api'

// ---------- 抽奖服务 /lottery ----------

export type ActivityStatus = 'NOT_STARTED' | 'ONGOING' | 'ENDED'
/** 奖品类型：VIRTUAL 虚拟权益（积分 / 次数）/ PHYSICAL 实物 / THANKS 谢谢参与（旧的 POINTS 已作废） */
export type PrizeType = 'VIRTUAL' | 'PHYSICAL' | 'THANKS'
/** 我的奖品四段状态（后端库值 待兑换/已兑换/已发货/已完成 的 HTTP 面映射） */
export type UserPrizeStatus = 'PENDING' | 'EXCHANGED' | 'SHIPPED' | 'COMPLETED'

export interface LotteryActivity {
  name: string
  startAt: string
  endAt: string
  status: ActivityStatus
  /** 用积分兑 1 次抽奖的单价（后端常量返回） */
  drawCountPrice: number
  /** 买来的次数有效期天数 */
  drawCountValidDays: number
}

export interface LotteryPrize {
  prizeId: string
  tier: number
  name: string
  type: PrizeType
  /** 仅虚拟积分档有值 */
  pointsAmount: number | null
  /** 台账剩余 / 投放总量；不限量档两者为 null 且 unlimited=true */
  remaining: number | null
  total: number | null
  unlimited: boolean
}

export interface DrawChances {
  available: number
  todayDrawn: number
}

export interface DrawResult {
  recordId: string
  prizeType: PrizeType
  prizeId: string | null
  tier: number
  name: string
  /** 积分档发放的积分数，其余为 null */
  pointsAmount: number | null
}

export interface UserPrize {
  id: string
  prizeId: string
  tier: number
  name: string
  prizeType: PrizeType
  status: UserPrizeStatus
  createdAt: string
  /** `EXCHANGE` 动作明细时刻；实物档当前恒为 null（它落 `SHIP` 不落 `EXCHANGE`），未推进也为 null */
  claimedAt: string | null
}

export const getActivity = () => httpGet<LotteryActivity | null>('/lottery/activity')

export const listPrizes = () => httpGet<LotteryPrize[]>('/lottery/prizes')

export const getDrawChances = () => httpGet<DrawChances>('/lottery/draw-chances')

/** 幂等：token = 前端生成的 uuid，撞键服务端回放已存结果。silent：NO_CHANCE 要出专用弹窗，不能先默认 toast */
export const draw = (token: string) => httpPost<DrawResult>('/lottery/draw', { token }, { silent: true })

export const listMyPrizes = (status: UserPrizeStatus, page = 1, pageSize = 20) =>
  httpGet<PageResult<UserPrize>>('/lottery/my-prizes', { status, page, pageSize })

/** 确认收货（票 14 发货闭环）：已发货 → 已完成；幂等，重复调用仍返回 COMPLETED */
export const confirmReceived = (id: string) =>
  httpPost<{ id: string; status: UserPrizeStatus }>(`/lottery/my-prizes/${id}/receive`)

/** 用积分购买抽奖次数：token 是「这一次购买意图」的 uuid，网络重试必须带同一个（否则重复扣积分）.
 * 后端同一实现也挂在 /lottery/purchase/draw-count；抽奖本身只扣次数、不扣积分。 */
export const exchangeDrawCount = (token: string) =>
  httpPost<{ redeemOrderId: string; drawCount: number }>(
    '/lottery/exchange/draw-count',
    { token },
    { silent: true }
  )

// ---------- 积分服务 /points ----------

export const getPointsBalance = () => httpGet<{ balance: number }>('/points/balance')

// ---------- 失败码与文案（与后端 ResponseCodeEnum 逐字对齐） ----------

/** 抽奖业务码（marketing-types ResponseCodeEnum 的 4xxx 段） */
export const LOTTERY_CODE = {
  ACTIVITY_NOT_STARTED: '4301',
  ACTIVITY_ENDED: '4302',
  NO_CHANCE: '4303',
  DAILY_LIMIT_REACHED: '4304',
  PRIZE_NOT_FOUND: '4305',
  /** 积分不够兑次数（4306，文案与积分服务 4101 一致） */
  INSUFFICIENT_POINTS: '4306',
  /** 概率表未预热，服务端降级中 */
  WARMUP_MISSING: '4311',
  /** 库存预扣与兜底都扣不出来：本次发放失败 */
  ISSUE_FAILED: '4313',
  /** 确认收货：不存在 / 非本人 / 状态不是已发货 */
  PRIZE_NOT_RECEIVABLE: '4314'
} as const

/** 购买次数失败业务码 → 专用弹窗文案；未收录的码由调用方落回默认 toast */
export const EXCHANGE_FAIL_TEXT: Record<string, { title: string; sub: string }> = {
  '4306': { title: '积分不足', sub: '抽奖中的积分、攒一攒再来购买' },
  '4302': { title: '活动已结束', sub: '购买通道已关闭' },
  '4301': { title: '活动还没开始', sub: '开始后再来购买吧' },
  '4311': { title: '活动准备中', sub: '抽奖活动正在准备，稍后再试' }
}

/** 档位文案：种子 tier 1~5（1 一等奖 … 5 五等奖）。原型里的奖品位是整块色底 + 档位大字，
 *  票 13 起目录/我的奖品都不再有 image 载体，故页面按 `{{ tier }} 等奖` 现拼，本常量留给后续需要整词的地方。 */
export const tierText = (tier: number): string => `${tier} 等奖`

/** 奖品色块底色（照原型的 tint 调色板，按奖品顺序循环取色） */
export const PRIZE_TINTS = ['#ffe7ec', '#e8f1ff', '#fff4e0', '#eaf7ee', '#f1ecff']
