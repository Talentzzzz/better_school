import { createReviewWsTicket } from '@/api/review'
import { useAuthStore } from '@/stores/auth'
import { mainSiteLoginUrl } from '@/utils/mainSite'
import { toast } from '@/components/Toast'

// 审核长连接客户端（票 12 接真链路）。
// 骨架与 front/src/utils/chatWs.ts 同款（唯一事实源：后端 /ws/review，票 10/11）：
//   - 一次性票据鉴权（POST /api/moderation/ws-ticket，requireAuditor + 握手 AUDITOR/ADMIN 角色过滤）
//   - 心跳 25s PING（与 chat 同口径，CONTEXT.md「心跳」）；退避重连 1s×2 上限 30s 带抖动
//   - KICK / close(4001) 停止重连并回主站登录
// 帧型差异：REVIEW_SIGNAL 载荷 { backlog: 总积压数 }——粗粒度总信号，只唤醒、不指定给谁、不锁单。

interface ReviewWsEnvelope {
  type: 'PING' | 'PONG' | 'REVIEW_SIGNAL' | 'KICK'
  payload?: { backlog?: number } | { reason?: string }
  ts?: number
}

type Handler = (env: ReviewWsEnvelope) => void

export type WsStatus = 'idle' | 'connecting' | 'open' | 'retrying' | 'stopped'

const HEARTBEAT_MS = 25_000
const BACKOFF_BASE_MS = 1_000
const BACKOFF_MAX_MS = 30_000
const JITTER_MS = 500

class ReviewWsClient {
  private ws: WebSocket | null = null
  private status: WsStatus = 'idle'
  private attempts = 0
  private reachedCap = false
  private heartbeatTimer: number | null = null
  private reconnectTimer: number | null = null
  private handlers = new Map<string, Set<Handler>>()
  private statusListeners = new Set<(s: WsStatus) => void>()

  constructor() {
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') this.retryNow()
    })
    window.addEventListener('online', () => this.retryNow())
  }

  on(type: ReviewWsEnvelope['type'], handler: Handler): () => void {
    if (!this.handlers.has(type)) this.handlers.set(type, new Set())
    this.handlers.get(type)!.add(handler)
    return () => this.handlers.get(type)?.delete(handler)
  }

  onStatus(listener: (s: WsStatus) => void): () => void {
    this.statusListeners.add(listener)
    return () => this.statusListeners.delete(listener)
  }

  getStatus(): WsStatus {
    return this.status
  }

  private setStatus(s: WsStatus) {
    this.status = s
    for (const l of this.statusListeners) l(s)
  }

  async connect() {
    if (this.status === 'open' || this.status === 'connecting') return
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.setStatus('connecting')
    let ticket: string
    try {
      ticket = (await createReviewWsTicket()).ticket
    } catch {
      // 票据获取失败（401 已由 http 拦截器回主站登录），按连接失败走退避
      this.scheduleReconnect()
      return
    }
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const socket = new WebSocket(`${proto}://${location.host}/ws/review?ticket=${encodeURIComponent(ticket)}`)
    this.ws = socket
    socket.onopen = () => {
      if (this.ws !== socket) return
      this.setStatus('open')
      this.attempts = 0
      this.reachedCap = false
      this.startHeartbeat()
    }
    socket.onmessage = (ev) => {
      if (this.ws !== socket) return
      try {
        const env = JSON.parse(ev.data as string) as ReviewWsEnvelope
        if (env.type === 'KICK') this.onKicked()
        else this.dispatch(env)
      } catch {
        // 非法帧忽略
      }
    }
    socket.onclose = (ev) => {
      if (this.ws !== socket) return
      this.ws = null
      this.stopHeartbeat()
      if (this.status === 'idle') return
      if (ev.code === 4001) {
        this.onKicked()
        return
      }
      this.scheduleReconnect()
    }
    socket.onerror = () => {
      // onclose 会跟随触发，交给 onclose 处理
    }
  }

  disconnect() {
    this.setStatus('idle')
    this.attempts = 0
    this.reachedCap = false
    this.stopHeartbeat()
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.closeSocket()
  }

  // visibilitychange/网络恢复立即重试（含退避到顶停下的 stopped 态）
  private retryNow() {
    if (this.status === 'open' || this.status === 'connecting' || this.status === 'idle') return
    this.connect()
  }

  private scheduleReconnect() {
    if (this.status === 'idle') return
    const base = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * 2 ** this.attempts)
    this.attempts += 1
    if (base >= BACKOFF_MAX_MS && this.reachedCap) {
      // 退避到顶仍失败：停在断开；网络恢复/切回前台可再试
      this.setStatus('stopped')
      toast('审核长连接已断开，请刷新页面重试')
      return
    }
    if (base >= BACKOFF_MAX_MS) this.reachedCap = true
    const delay = base + Math.random() * JITTER_MS
    this.setStatus('retrying')
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, delay)
  }

  private startHeartbeat() {
    this.stopHeartbeat()
    this.heartbeatTimer = window.setInterval(() => {
      if (this.ws?.readyState === WebSocket.OPEN) {
        this.ws.send(JSON.stringify({ type: 'PING' }))
      }
    }, HEARTBEAT_MS)
  }

  private stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  private closeSocket() {
    const socket = this.ws
    this.ws = null
    if (socket) {
      socket.onopen = null
      socket.onmessage = null
      socket.onclose = null
      socket.onerror = null
      socket.close()
    }
  }

  private onKicked() {
    if (this.status === 'stopped' || this.status === 'idle') return
    this.setStatus('stopped')
    this.stopHeartbeat()
    this.closeSocket()
    toast('账号已在其他设备登录')
    useAuthStore().clearAuth()
    window.location.href = mainSiteLoginUrl()
  }

  private dispatch(env: ReviewWsEnvelope) {
    const set = this.handlers.get(env.type)
    if (!set) return
    for (const handler of set) handler(env)
  }
}

export const reviewWs = new ReviewWsClient()
