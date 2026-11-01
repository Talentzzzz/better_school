// 审核中台 · 领域类型
//
// ⚠️ 共享类型手抄口径（spec §5.4 / 票 12）：后端 Java 枚举与 DTO 为**唯一事实源**，
// 本文件手抄、两侧注释互指；改后端枚举必须同步改这里与 front/src/types/api.ts。
// 对应后端：bs-types 的 ReviewTargetTypeVO / RejectReasonCode、bs-api 的 ReviewTask*ResponseDTO。

/**
 * 同屏审核的内容类型（4 类可审值）。
 * 唯一事实源：后端 ReviewTargetTypeVO（POST/COMMENT/PRODUCT/ERRAND；
 * USER/MESSAGE 仅举报用、不入池）。与 front/src/types/api.ts 的 ReviewTargetType 同抄。
 */
export type ReviewTargetType = 'POST' | 'COMMENT' | 'PRODUCT' | 'ERRAND'

/** 拒绝理由统一枚举（全站口径）。唯一事实源：后端 bs-types RejectReasonCode.java（8 值） */
export type RejectReasonCode =
  | 'POLITICAL'
  | 'PORNOGRAPHY'
  | 'AD_PROMOTION'
  | 'PROHIBITED_ITEM'
  | 'FRAUD'
  | 'HARASSMENT'
  | 'INFRINGEMENT'
  | 'OTHER'

/** 工单态（唯一事实源：review_task.claim_state，spec §5.2；勿与业务表 state 混用） */
export type ClaimState = 'PENDING' | 'CLAIMED' | 'DONE'

/** 裁决动作（唯一事实源：ReviewActionVO.APPROVE / REJECT） */
export type ReviewAction = 'APPROVE' | 'REJECT'

export interface TypeMeta {
  key: ReviewTargetType
  label: string
  /** 徽标主色 */
  color: string
  /** 徽标底色 */
  soft: string
}

/** 类型元数据：同屏四类的展示口径 */
export const TYPES: TypeMeta[] = [
  { key: 'POST', label: '帖子', color: '#4c8dff', soft: 'rgba(76,141,255,.15)' },
  { key: 'COMMENT', label: '评论', color: '#22b8cf', soft: 'rgba(34,184,207,.15)' },
  { key: 'PRODUCT', label: '商品', color: '#f5a623', soft: 'rgba(245,166,35,.16)' },
  { key: 'ERRAND', label: '跑腿', color: '#ff2442', soft: 'rgba(255,36,66,.15)' }
]

export function typeMeta(t: ReviewTargetType): TypeMeta {
  return TYPES.find((x) => x.key === t) ?? TYPES[0]
}

/** 拒绝理由 chip（顺序与文案对齐后端枚举与主前端审核口径） */
export const REJECT_REASONS: { code: RejectReasonCode; label: string }[] = [
  { code: 'POLITICAL', label: '涉政有害' },
  { code: 'PORNOGRAPHY', label: '色情低俗' },
  { code: 'AD_PROMOTION', label: '广告导流' },
  { code: 'PROHIBITED_ITEM', label: '违禁品交易' },
  { code: 'FRAUD', label: '诈骗导流' },
  { code: 'HARASSMENT', label: '人身攻击' },
  { code: 'INFRINGEMENT', label: '侵权' },
  { code: 'OTHER', label: '其他' }
]

export function reasonLabel(code: RejectReasonCode): string {
  return REJECT_REASONS.find((r) => r.code === code)?.label ?? code
}

/**
 * 领单结果（手抄自后端 ReviewTaskClaimResponseDTO，票 08）。
 * 内容素材不在此——前端领单后凭 taskId 再走 GET /tasks/{taskId} 取聚合详情。
 */
export interface ReviewTaskClaim {
  taskId: number
  targetType: ReviewTargetType
  targetId: number
  /** 审核轮次（COMMENT/ERRAND 恒 1；POST/PRODUCT 重提递增） */
  reviewRound: number
  claimState: ClaimState
  /** 领单轮次（租约复合 bizId {taskId}:{claimSeq} 的轮次，前端只读） */
  claimSeq: number
  /** 租约到期时刻（ISO LocalDateTime；前端倒计时锚点） */
  leaseExpireAt: string
  /** 回收次数（≥3 置 escalated + 告警，spec §5.2） */
  recycleCount: number
  escalated: boolean
  /** 进入待审时刻（FIFO 排序锚点；回收不改，等待时长继续累加） */
  enteredAt: string
}

/** 历史裁决条目（手抄自后端 ReviewHistoryItemResponseDTO；同内容历轮 + 同作者两路共用） */
export interface ReviewHistoryItem {
  reviewId: number
  targetType: ReviewTargetType
  targetId: number
  reviewRound: number
  action: ReviewAction
  /** 拒绝理由全文（落库为「枚举文案：补充说明」；通过为空） */
  reason: string | null
  reasonCode: RejectReasonCode | null
  operatorId: number | null
  createdAt: string
}

/**
 * 工单详情聚合（手抄自后端 ReviewTaskDetailResponseDTO，票 07）：
 * 通用列 + 类型专属上下文 + 两路历史裁决；纯只读。
 * 风险标记（原型 riskFlags 占位）后端不落列、派生口径归 spec §5.1 开放项，故无此字段。
 */
export interface ReviewTaskDetail {
  taskId: number
  targetType: ReviewTargetType
  targetId: number
  reviewRound: number
  claimState: ClaimState
  enteredAt: string
  decidedAt: string | null
  authorId: number
  authorNickname: string | null
  authorAvatar: string | null
  /** 内容全文（帖=标题+正文，商=标题+描述，评=评论文本，跑腿=描述） */
  content: string
  images: string[] | null
  /** 跑腿悬赏 / 商品价格（沿用审核队列 reward 列口径；帖/评为空） */
  reward: number | null
  /** 业务行当前状态（只读展示） */
  status: string | null
  // ---- 评论类型专属上下文（其余类型为 null） ----
  contextPostId: number | null
  contextPostTitle: string | null
  /** 父评论业务 ID（根评论为 null） */
  contextParentId: number | null
  contextParentContent: string | null
  /** 同一内容历轮裁决（review_round 升序） */
  targetReviews: ReviewHistoryItem[]
  /** 同一作者历史裁决（最新 20 条） */
  authorReviews: ReviewHistoryItem[]
}

/** 各类型积压计数（GET /tasks/counts；后端 Map<String,Long>，key=ReviewTargetType.name()） */
export type BacklogCounts = Record<ReviewTargetType, number>

export function emptyCounts(): BacklogCounts {
  return { POST: 0, COMMENT: 0, PRODUCT: 0, ERRAND: 0 }
}

export type EventKind = 'signal' | 'claim' | 'pass' | 'reject' | 'release' | 'recycle' | 'presence'

/** 派单事件流（把「信号 → 拉单 → 裁决/放回 → 回收」做成看得见的日志；前端本地记录） */
export interface AuditEvent {
  id: number
  at: number
  kind: EventKind
  text: string
}

/** 本会话的裁决记录（仅前端统计「我的处理量」用；权威数据在 content_review） */
export interface DecisionRecord {
  taskId: number
  targetType: ReviewTargetType
  action: 'PASS' | 'REJECT'
  reasonCode?: RejectReasonCode
  supplement?: string
  /** 从拉到手到裁决耗时（秒） */
  seconds: number
  at: number
}
