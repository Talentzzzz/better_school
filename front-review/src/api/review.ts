import { httpGet, httpPost } from './http'
import type {
  ReviewAction,
  ReviewTaskClaim,
  ReviewTaskDetail,
  RejectReasonCode,
  ReviewTargetType
} from '@/types/review'

// 审核工单 API（票 12 接真链路）。唯一事实源：后端 ModerationController /api/moderation/tasks/**（票 06–09 落地）。

/** 各类型积压计数（驱动顶部类型计数条；派单信号 payload 只带总数，分类型走这里） */
export function fetchTaskCounts() {
  return httpGet<Partial<Record<ReviewTargetType, number>>>('/moderation/tasks/counts')
}

/** 拉一条（原子指派 + 起租约；类型过滤可选，中台按 spec §5.3 一律全量 FIFO 不挑类型）。池空/并发落空 data=null */
export function claimTask() {
  return httpPost<ReviewTaskClaim | null>('/moderation/tasks/claim', {})
}

/** 单条详情聚合（内容/作者/类型专属上下文/两路历史裁决） */
export function fetchTaskDetail(taskId: number) {
  return httpGet<ReviewTaskDetail>(`/moderation/tasks/${taskId}`)
}

/** 裁决（强绑定：仅持有人在租约内；拒绝时 reasonCode 必填） */
export function decideTask(
  taskId: number,
  approve: boolean,
  reasonCode?: RejectReasonCode,
  supplement?: string
) {
  const body: { action: ReviewAction; reasonCode?: string; supplement?: string } = {
    action: approve ? 'APPROVE' : 'REJECT'
  }
  if (!approve && reasonCode) body.reasonCode = reasonCode
  if (!approve && supplement?.trim()) body.supplement = supplement.trim()
  return httpPost<null>(`/moderation/tasks/${taskId}/decide`, body)
}

/** 主动放回（审不了 → CLAIMED→PENDING，recycle_count+1） */
export function releaseTask(taskId: number) {
  return httpPost<null>(`/moderation/tasks/${taskId}/release`, {})
}

/** 审核长连接票据（端点无关能力，chat/review 共用同一签发实现；review 端点握手叠加 AUDITOR/ADMIN 角色过滤） */
export function createReviewWsTicket() {
  return httpPost<{ ticket: string }>('/moderation/ws-ticket')
}

/** 队列首页 1 条：取「最早已等待」锚点（只读计数条的辅助口径，借既有 GET /moderation/queue） */
export function fetchOldestQueueItem() {
  return httpGet<{ list: { enteredAt: string }[] }>('/moderation/queue', { page: 1, pageSize: 1 })
}
