import type { PostSummary } from '@/types/api'

/** 草稿箱收纳的帖子状态：编辑中（DRAFT）、被拒转入（REJECTED）、待审可撤回（PENDING_REVIEW） */
export const DRAFT_BOX_STATUSES = ['DRAFT', 'REJECTED', 'PENDING_REVIEW'] as const

/** 帖子状态文案（"我的-发帖"与草稿箱共用） */
export const POST_STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿',
  REJECTED: '被拒',
  PENDING_REVIEW: '待审'
}

export function isInDraftBox(post: PostSummary) {
  return DRAFT_BOX_STATUSES.includes((post.status ?? '') as (typeof DRAFT_BOX_STATUSES)[number])
}
