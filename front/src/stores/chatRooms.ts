import { defineStore } from 'pinia'
import { hideRoom as apiHideRoom, listRooms, markRead as apiMarkRead, pullTimeline } from '@/api/chat'
import type { ChatMessage, ChatRoomSummary } from '@/types/api'
import { wsClient } from '@/utils/ws'

const TIMELINE_LIMIT = 200
const REFRESH_DEBOUNCE_MS = 500

function previewOf(msg: ChatMessage): string {
  switch (msg.msgType) {
    case 'IMAGE':
      return '[图片]'
    case 'CARD':
      return msg.refType === 'PRODUCT' ? '[商品]' : msg.refType === 'POST' ? '[帖子]' : '[跑腿卡片]'
    case 'SYSTEM':
      return '[系统通知]'
    default:
      return msg.content
  }
}

export const useChatRoomsStore = defineStore('chatRooms', {
  state: () => ({
    rooms: [] as ChatRoomSummary[],
    loaded: false,
    // delivery_seq 补拉游标：内存持有不持久化，刷新后 0=全量永远正确（spec §2）
    cursor: 0,
    pulling: false,
    // 每会话已应用的最大 seq：MSG 按 (roomId, seq) 判重，推送与补拉竞争合流
    appliedSeq: {} as Record<number, number>,
    // 成员水位 map[roomId][userId]=readSeq：READ 推送就地更新（详情页已读回执推导用，spec §7.5）
    readSeqs: {} as Record<number, Record<number, number>>,
    // 判重后的消息出口（推送+补拉统一），详情页订阅渲染气泡
    msgListeners: [] as Array<(msg: ChatMessage) => void>,
    unbindFns: [] as Array<() => void>,
    refreshTimer: null as number | null,
    inited: false
  }),
  getters: {
    unreadTotal: (s) => s.rooms.reduce((sum, r) => sum + r.unread, 0),
    roomById: (s) => (roomId: number) => s.rooms.find((r) => r.roomId === roomId)
  },
  actions: {
    /** 登录后初始化：绑定 WS 事件 → 拉列表（首屏数据）→ 后台补拉对齐（不阻塞渲染） */
    async init() {
      if (this.inited) return
      this.inited = true
      this.bindWs()
      await this.loadRooms()
      this.syncLoop()
    },

    reset() {
      this.inited = false
      this.rooms = []
      this.loaded = false
      this.cursor = 0
      this.pulling = false
      this.appliedSeq = {}
      this.readSeqs = {}
      this.msgListeners = []
      if (this.refreshTimer) {
        clearTimeout(this.refreshTimer)
        this.refreshTimer = null
      }
      for (const unbind of this.unbindFns) unbind()
      this.unbindFns = []
    },

    bindWs() {
      this.unbindFns.push(
        wsClient.on('MSG', (env) => {
          // payload=完整消息体（含 roomId/seq 判重键），客户端按 (roomId, seq) 判重
          const msg = env.payload as ChatMessage | undefined
          if (msg?.roomId && msg.seq) this.applyMessage(msg, true)
        }),
        wsClient.on('READ', (env) => {
          // payload={roomId, userId(readerId), readSeq}，就地更新成员水位 map
          const payload = env.payload as { roomId: number; userId: number; readSeq: number } | undefined
          if (payload?.roomId) this.mergeReadSeq(payload.roomId, payload.userId, payload.readSeq)
        }),
        wsClient.on('RECALL', (env) => {
          // payload={roomId, seq}：轻事件只带定位，内容终态以拉取为准
          const payload = env.payload as { roomId: number; seq: number } | undefined
          if (payload?.roomId && payload.seq) this.applyRecall(payload.roomId, payload.seq)
        }),
        // 断线重连对齐：连接成功即补拉（推是加速，拉是真相，spec §2）
        wsClient.onOpen(() => this.syncLoop())
      )
    },

    async loadRooms() {
      try {
        const res = await listRooms(1, 50)
        this.rooms = res.list
        this.loaded = true
      } catch {
        // toast 已弹
      }
    },

    /** 补拉循环：hasMore 为显式追平信号（不再用「返回条数 < limit」推断）；游标跟服务端扫描边界走 */
    async syncLoop() {
      if (this.pulling) return
      this.pulling = true
      try {
        // 首轮补拉（cursor 0=全量）期间未读以 loadRooms 服务端值为准，不逐条累加
        const initial = this.cursor === 0
        for (;;) {
          const page = await pullTimeline(this.cursor, TIMELINE_LIMIT)
          for (const msg of page.list) this.applyMessage(msg, !initial)
          // 取 nextCursor 而非最后一条的 deliverySeq：服务端跳过漂移行时游标照样前进
          this.cursor = Math.max(this.cursor, page.nextCursor)
          if (!page.hasMore) break
        }
      } catch {
        // 补拉失败不致命：下次重连/刷新再对齐
      } finally {
        this.pulling = false
      }
    },

    /**
     * 应用一条消息（推送或补拉）：按 seq 判重，就地更新 last_msg/未读/置顶。
     * 未读累加口径与服务端一致（unread=last_seq−水位线，自己发的消息也占未读直到 markRead）
     */
    applyMessage(msg: ChatMessage, countUnread: boolean) {
      const applied = this.appliedSeq[msg.roomId] ?? 0
      if (msg.seq <= applied) return
      this.appliedSeq[msg.roomId] = msg.seq
      const room = this.roomById(msg.roomId)
      if (!room) {
        // 未知会话（新会话/被删会话弹回）：列表以服务端为准，去抖刷新
        this.scheduleRefresh()
        for (const listener of this.msgListeners) listener(msg)
        return
      }
      if (countUnread) room.unread += 1
      room.lastMsgType = msg.msgType
      room.lastMsgPreview = previewOf(msg)
      room.lastMsgAt = msg.createdAt
      // 置顶重排
      this.rooms = [room, ...this.rooms.filter((r) => r.roomId !== msg.roomId)]
      // 通知放状态更新之后：详情页依赖 unread 已就绪再决定是否推水位线
      for (const listener of this.msgListeners) listener(msg)
    },

    applyRecall(roomId: number, seq: number) {
      const room = this.roomById(roomId)
      // 只有撤的是会话最后一条才需要就地改预览；更早的消息预览不展示
      if (room && seq === (this.appliedSeq[roomId] ?? 0)) {
        room.lastMsgPreview = '[已撤回]'
        room.lastMsgType = 'TEXT'
      }
    },

    /** 订阅判重后的消息流（推送+补拉统一出口），返回解绑函数 */
    onMessage(cb: (msg: ChatMessage) => void): () => void {
      this.msgListeners.push(cb)
      return () => {
        const i = this.msgListeners.indexOf(cb)
        if (i >= 0) this.msgListeners.splice(i, 1)
      }
    },

    /** 进详情页清未读：本地立即归零 + 推水位线（幂等，仅前进才 UPDATE） */
    async markRead(roomId: number) {
      const room = this.roomById(roomId)
      if (room) room.unread = 0
      try {
        await apiMarkRead(roomId)
      } catch {
        // toast 已弹
      }
    },

    /** 长按删除会话：本地立即消失 + POST hide；新消息推送清 hidden_at 弹回（store 侧经 scheduleRefresh 自然恢复） */
    async hideRoom(roomId: number) {
      this.rooms = this.rooms.filter((r) => r.roomId !== roomId)
      try {
        await apiHideRoom(roomId)
      } catch {
        // toast 已弹；本地已移除，下次 loadRooms 以服务端为准
      }
    },

    /** 成员水位 map 就地合并（仅前进）：READ 推送与详情页 members 初值共用 */
    mergeReadSeq(roomId: number, userId: number, readSeq: number) {
      const map = (this.readSeqs[roomId] ??= {})
      map[userId] = Math.max(map[userId] ?? 0, readSeq)
    },

    scheduleRefresh() {
      if (this.refreshTimer) return
      this.refreshTimer = window.setTimeout(() => {
        this.refreshTimer = null
        this.loadRooms()
      }, REFRESH_DEBOUNCE_MS)
    }
  }
})
