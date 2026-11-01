import { httpGet, httpPost } from './http'
import type {
  PageResult,
  ChatRoom,
  ChatRoomSummary,
  ChatMessage,
  RoomMembers,
  Notification,
  RoomSource
} from '@/types/api'

export function listRooms(page = 1, pageSize = 50) {
  return httpGet<PageResult<ChatRoomSummary>>('/chat/rooms', { page, pageSize })
}

/** 建/取 1v1 会话：source 仅市集两入口传（首页与用户主页私信入口不传），只在首建时生效 */
export function createDirectRoom(targetUserId: number, source?: RoomSource) {
  return httpPost<ChatRoom>('/chat/rooms/direct', { targetUserId, source })
}

export function listMessages(roomId: number, params: { beforeSeq?: number; limit?: number } = {}) {
  return httpGet<ChatMessage[]>(`/chat/rooms/${roomId}/messages`, { ...params })
}

export interface SendMessageBody {
  clientMsgId: string
  msgType: 'TEXT' | 'IMAGE' | 'CARD'
  content?: string
  refType?: string
  refId?: number
}

export function sendMessage(roomId: number, body: SendMessageBody) {
  return httpPost<ChatMessage>(`/chat/rooms/${roomId}/messages`, body)
}

export function markRead(roomId: number) {
  return httpPost<number>(`/chat/rooms/${roomId}/read`)
}

export function recallMessage(roomId: number, seq: number) {
  return httpPost<null>(`/chat/rooms/${roomId}/messages/${seq}/recall`)
}

export function hideRoom(roomId: number) {
  return httpPost<null>(`/chat/rooms/${roomId}/hide`)
}

/** 会话成员（1v1 恒两人）：已读水位线初值，见 spec §7.5 */
export function listMembers(roomId: number) {
  return httpGet<RoomMembers>(`/chat/rooms/${roomId}/members`)
}

export function pullTimeline(afterDeliverySeq: number, limit = 200) {
  return httpGet<ChatMessage[]>('/chat/timeline', { afterDeliverySeq, limit })
}

export function createWsTicket() {
  return httpPost<{ ticket: string }>('/chat/ws-ticket')
}

export function listNotifications(page = 1, pageSize = 20, scope?: string) {
  return httpGet<PageResult<Notification>>('/chat/notifications', { page, pageSize, scope })
}

/** 按范围推已读水位（scope+水位线读模型） */
export function markNotificationsRead(scope: string) {
  return httpPost<null>('/chat/notifications/read', { scope })
}

/** 某范围的未读数（= 取号计数器 − 水位，O(1)） */
export function getNotificationsUnreadCount(scope: string) {
  return httpGet<{ count: number }>('/chat/notifications/unread-count', { scope })
}
