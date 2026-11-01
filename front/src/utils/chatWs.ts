import { createWsTicket } from '@/api/chat'
import { useAuthStore } from '@/stores/auth'
import router from '@/router'
import { toast } from '@/components/Toast'

interface WsEnvelope {
  type: 'PING' | 'PONG' | 'MSG' | 'READ' | 'RECALL' | 'KICK'
  roomId?: number
  seq?: number
  payload?: unknown
  ts?: number
}

type Handler = (env: WsEnvelope) => void
type OpenHandler = () => void

// spec §3：心跳 25s；退避重连 1s×2 上限 30s 带抖动；KICK/close(4001) 停止重连
const HEARTBEAT_MS = 25_000
const BACKOFF_BASE_MS = 1_000
const BACKOFF_MAX_MS = 30_000
const JITTER_MS = 500

type Status = 'idle' | 'connecting' | 'open' | 'retrying' | 'stopped'

class ChatWsClient {
  private ws: WebSocket | null = null
  private status: Status = 'idle'
  private attempts = 0
  private reachedCap = false
  private heartbeatTimer: number | null = null
  private reconnectTimer: number | null = null
  private handlers = new Map<string, Set<Handler>>()
  private openListeners = new Set<OpenHandler>()

  constructor() {
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') this.retryNow()
    })
    window.addEventListener('online', () => this.retryNow())
  }

  on(type: WsEnvelope['type'], handler: Handler): () => void {
    if (!this.handlers.has(type)) this.handlers.set(type, new Set())
    this.handlers.get(type)!.add(handler)
    return () => this.handlers.get(type)?.delete(handler)
  }

  /** 每次连接成功（含重连）回调，供上层做断线重连对齐 */
  onOpen(handler: OpenHandler): () => void {
    this.openListeners.add(handler)
    return () => this.openListeners.delete(handler)
  }

  async connect() {
    if (this.status === 'open' || this.status === 'connecting') return
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.status = 'connecting'
    let ticket: string
    try {
      ticket = (await createWsTicket()).ticket
    } catch {
      // 票据获取失败（含 401 已由 http 拦截器处理跳登录），按连接失败走退避
      this.scheduleReconnect()
      return
    }
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const socket = new WebSocket(`${proto}://${location.host}/ws/chat?ticket=${encodeURIComponent(ticket)}`)
    this.ws = socket
    socket.onopen = () => {
      if (this.ws !== socket) return
      this.status = 'open'
      this.attempts = 0
      this.reachedCap = false
      this.startHeartbeat()
      for (const handler of this.openListeners) handler()
    }
    socket.onmessage = (ev) => {
      if (this.ws !== socket) return
      try {
        const env = JSON.parse(ev.data as string) as WsEnvelope
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
      // 主动断开（登出/reset）：status 已被置 idle，不重连
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
    this.status = 'idle'
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
      // 退避到顶仍失败：停在断开，提示重新登录；网络恢复/切回前台可再试
      this.status = 'stopped'
      toast('连接已断开，请重新登录')
      return
    }
    if (base >= BACKOFF_MAX_MS) this.reachedCap = true
    const delay = base + Math.random() * JITTER_MS
    this.status = 'retrying'
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
    this.status = 'stopped'
    this.stopHeartbeat()
    this.closeSocket()
    toast('账号已在其他设备登录')
    const auth = useAuthStore()
    auth.clearAuth()
    router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  }

  private dispatch(env: WsEnvelope) {
    const set = this.handlers.get(env.type)
    if (!set) return
    for (const handler of set) handler(env)
  }
}

export const chatWs = new ChatWsClient()
