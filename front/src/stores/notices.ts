import { defineStore } from 'pinia'
import { getNotificationsUnreadCount, listNotifications } from '@/api/chat'
import type { Notification } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { chatWs } from '@/utils/chatWs'

/**
 * 站内通知呈现层 store：
 * - BANNER 通道（点赞/评论/回复）：WS NOTIF 帧在线即弹横幅 + 对应 scope 未读角标 +1；
 *   「保证弹过」——本地按 (userId, scope) 记 seq 水位（localStorage），断线重连/上线时把
 *   seq > 水位的积压通知逐条重放弹一遍（小红书口径：弹过就没，但必须弹过）
 * - CHAT 通道（订单动态/信用分）：不经本 store——走系统助手会话（聊天链路自带实时冒泡与补拉）
 * - NONE 通道（中奖/积分/公告）：只落通知中心，进页面拉取可见
 */
export const useNoticesStore = defineStore('notices', {
  state: () => ({
    // 各范围未读数（服务端水位线口径；NOTIF 帧就地 +1，进通知页/markRead 后重拉对齐）
    unread: {
      INTERACTION_LIKE: 0,
      INTERACTION_COMMENT: 0,
      BOTTOM_SYSTEM: 0
    } as Record<string, number>,
    // 横幅队列（先进先出，NotifBanner 组件渲染并逐个自动消失）
    banners: [] as NoticeBanner[],
    inited: false,
    unbindFns: [] as Array<() => void>
  }),
  getters: {
    unreadTotal: (s) => Object.values(s.unread).reduce((sum, n) => sum + n, 0)
  },
  actions: {
    /** 登录后初始化：绑定 WS 事件 → 拉未读 → 补放积压横幅 */
    async init() {
      if (this.inited) return
      this.inited = true
      this.bindWs()
      await this.refreshUnread()
      void this.replayBanners()
    },

    reset() {
      this.inited = false
      this.unread = { INTERACTION_LIKE: 0, INTERACTION_COMMENT: 0, BOTTOM_SYSTEM: 0 }
      this.banners = []
      for (const unbind of this.unbindFns) unbind()
      this.unbindFns = []
    },

    bindWs() {
      this.unbindFns.push(
        chatWs.on('NOTIF', (env) => {
          const payload = env.payload as NoticeNotifPayload | undefined
          if (payload) this.handleNotif(payload)
        }),
        // 断线重连对齐：未读以服务端为准，横幅补放水位之后的部分
        chatWs.onOpen(() => {
          void this.refreshUnread()
          void this.replayBanners()
        })
      )
    },

    /** NOTIF 帧到达（在线实时路径）：未读角标就地 +1 + 入横幅队列 + 水位推进 */
    handleNotif(payload: NoticeNotifPayload) {
      if (this.unread[payload.scope] !== undefined) this.unread[payload.scope] += 1
      if (BANNER_SCOPES.includes(payload.scope)) {
        this.banners.push({
          id: `${payload.scope}:${payload.seq}`,
          scope: payload.scope,
          type: payload.type,
          title: payload.title,
          content: payload.content,
          refType: payload.refType || null,
          refId: payload.refId || null
        })
        if (this.banners.length > MAX_QUEUED_BANNERS) {
          // 队列上限保护：极陡的赞潮丢弃最旧的（通知中心仍完整可查）
          this.banners.splice(0, this.banners.length - MAX_QUEUED_BANNERS)
        }
        this.saveCursor(payload.scope, payload.seq)
      }
    },

    async refreshUnread() {
      for (const scope of Object.keys(this.unread)) {
        try {
          const res = await getNotificationsUnreadCount(scope)
          this.unread[scope] = res.count
        } catch {
          // toast 已弹；下次重连/刷新再对齐
        }
      }
    },

    /**
     * 横幅补放（保证弹过）：按 scope 拉通知列表，seq > 本地水位的逐条入队（旧→新）。
     * 本地无水位（本机首次登录该账号）静默对齐到当前最新——不回放历史存量
     */
    async replayBanners() {
      for (const scope of BANNER_SCOPES) {
        const cursor = this.loadCursor(scope)
        try {
          if (cursor === null) {
            // 本机首次登录该账号：水位对齐到当前最大 seq，历史存量不回放
            const latest = await listNotifications(1, 1, scope)
            if (latest.list.length) this.saveCursor(scope, latest.list[0].seq)
            continue
          }
          // 已有水位：翻页收集 seq > 水位的积压通知（旧→新重放）
          const pending: Notification[] = []
          let scanned = 0
          for (let page = 1; page <= REPLAY_MAX_PAGES; page++) {
            const res = await listNotifications(page, REPLAY_PAGE_SIZE, scope)
            scanned += res.list.length
            pending.push(...res.list.filter((n) => n.seq > cursor))
            if (res.list.length === 0 || scanned >= res.total || res.list.every((n) => n.seq <= cursor)) break
          }
          pending.sort((a, b) => a.seq - b.seq)
          for (const n of pending) {
            this.banners.push({
              id: `${scope}:${n.seq}`,
              scope,
              type: n.type,
              title: n.title,
              content: n.content,
              refType: n.refType,
              refId: n.refId
            })
          }
          if (this.banners.length > MAX_QUEUED_BANNERS) {
            this.banners.splice(0, this.banners.length - MAX_QUEUED_BANNERS)
          }
          // 水位推进到服务端当前最大（积压全部补放完毕）
          const latest = await listNotifications(1, 1, scope)
          if (latest.list.length) this.saveCursor(scope, latest.list[0].seq)
        } catch {
          // 补放失败不致命：下次重连/刷新再对齐
        }
      }
    },

    dismissBanner(id: string) {
      this.banners = this.banners.filter((b) => b.id !== id)
    },

    loadCursor(scope: string): number | null {
      const raw = localStorage.getItem(cursorKey(scope))
      return raw === null ? null : Number(raw)
    },

    saveCursor(scope: string, seq: number) {
      localStorage.setItem(cursorKey(scope), String(seq))
    }
  }
})

export interface NoticeBanner {
  id: string
  scope: string
  type: string
  title: string
  content: string
  refType: string | null
  refId: number | null
}

interface NoticeNotifPayload {
  scope: string
  seq: number
  type: string
  title: string
  content: string
  refType?: string
  refId?: number
}

const BANNER_SCOPES = ['INTERACTION_LIKE', 'INTERACTION_COMMENT']
const MAX_QUEUED_BANNERS = 5
const REPLAY_PAGE_SIZE = 50
const REPLAY_MAX_PAGES = 5

/** 水位按账号隔离（localStorage 常驻）：换账号登录互不串扰，跨刷新持续有效 */
function cursorKey(scope: string): string {
  const auth = useAuthStore()
  return `bs:notify-cursor:${auth.userId ?? 'anon'}:${scope}`
}
