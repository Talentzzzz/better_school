import type { ChatRoomSummary } from '@/types/api'

/**
 * 会话展示名：1v1 用对方昵称（服务端拼装，spec §4）。
 * 昵称可空，`|| ''` 的兜底只此一处，免得 4 个视图各写一份。
 */
export function roomDisplayName(room: Pick<ChatRoomSummary, 'peerNickname'>): string {
  return room.peerNickname || ''
}

/** IMAGE 载荷为 JSON {url,width?,height?}（宽高可缺省），兼容裸 url */
export interface ImagePayload {
  url: string
  width?: number
  height?: number
}

export function parseImageContent(content: string): ImagePayload | null {
  try {
    const obj = JSON.parse(content) as ImagePayload
    if (obj && typeof obj.url === 'string') return obj
  } catch {
    // 落到裸 url 兜底
  }
  return content ? { url: content } : null
}
