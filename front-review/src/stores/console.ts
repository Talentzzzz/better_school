import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { reasonLabel, typeMeta } from '@/types/review'
import type {
  AuditEvent,
  BacklogCounts,
  DecisionRecord,
  EventKind,
  RejectReasonCode,
  ReviewTargetType,
  ReviewTaskClaim,
  ReviewTaskDetail
} from '@/types/review'
import { emptyCounts } from '@/types/review'
import { claimTask, decideTask, fetchOldestQueueItem, fetchTaskCounts, fetchTaskDetail, releaseTask } from '@/api/review'
import { ApiError } from '@/api/http'
import { reviewWs, type WsStatus } from '@/utils/reviewWs'
import { formatWait } from '@/utils/format'
import { toast } from '@/components/Toast'

/**
 * 审核中台 · 派单状态机（真链路，票 12）
 *
 * 复刻 spec §5.3 已定口径「推信号 + 拉工单」，服务端为唯一事实源（票 06–11）：
 *   - 推：/ws/review 的 REVIEW_SIGNAL 只带积压总数，只负责唤醒 → 信号条脉冲 + 计数条刷新。
 *   - 拉：POST /tasks/claim 原子指派，一次一条；拿到租约后由 leaseExpireAt 起本地倒计时。
 *   - 租约：到点服务端自动回收（REVIEW_LEASE_TIMEOUT），前端倒计时归零即按「已回收」处理，
 *     清空手中工单、刷新计数、回到「可再拉」。
 *   - presence：WS 连接即在线（服务端 Redis 记录），本 store 只透出连接状态。
 */

let leaseTimer: ReturnType<typeof setInterval> | null = null
let pulseTimer: ReturnType<typeof setTimeout> | null = null

let uid = 1
let currentStart = 0

export type BannerMode = 'offline' | 'busy' | 'signal' | 'empty'

/** 本地持有的工单 = 领单凭据 + 详情聚合 */
export interface CurrentTask {
  claim: ReviewTaskClaim
  detail: ReviewTaskDetail
}

export const useConsoleStore = defineStore('console', () => {
  // ---------- state ----------
  const wsStatus = ref<WsStatus>('idle')
  const counts = ref<BacklogCounts>(emptyCounts())
  /** 最早已等待工单的进入时刻（ms；来自 GET /queue 首条，只读计数条辅助口径） */
  const oldestEnteredAt = ref<number | null>(null)
  const current = ref<CurrentTask | null>(null)
  const leaseTotal = ref(0)
  const leaseLeft = ref(0)
  const decisions = ref<DecisionRecord[]>([])
  const reclaimed = ref(0)
  const events = ref<AuditEvent[]>([])
  /** 推送信号的脉冲（派单信号 / 审完可再拉 / 回收后闪一下） */
  const pulse = ref(false)

  // ---------- getters ----------
  const backlogTotal = computed(
    () => counts.value.POST + counts.value.COMMENT + counts.value.PRODUCT + counts.value.ERRAND
  )

  const doneCount = computed(() => decisions.value.length)
  const reclaimedCount = computed(() => reclaimed.value)
  const avgSeconds = computed(() => {
    if (!decisions.value.length) return 0
    const sum = decisions.value.reduce((a, d) => a + d.seconds, 0)
    return Math.round(sum / decisions.value.length)
  })

  const bannerMode = computed<BannerMode>(() => {
    if (wsStatus.value !== 'open') return 'offline'
    if (current.value) return 'busy'
    if (backlogTotal.value > 0) return 'signal'
    return 'empty'
  })

  const leaseRatio = computed(() =>
    leaseTotal.value ? Math.max(0, leaseLeft.value / leaseTotal.value) : 0
  )

  // ---------- internals ----------
  function pushEvent(kind: EventKind, text: string) {
    events.value.unshift({ id: uid++, at: Date.now(), kind, text })
    if (events.value.length > 60) events.value.pop()
  }

  function firePulse() {
    pulse.value = true
    if (pulseTimer) clearTimeout(pulseTimer)
    pulseTimer = setTimeout(() => (pulse.value = false), 4000)
  }

  /** 刷新积压计数 + 最早已等待锚点（信号 / 裁决 / 放回 / 回收 / 手动刷新都会走） */
  async function refresh() {
    try {
      const raw = await fetchTaskCounts()
      const next = emptyCounts()
      for (const key of Object.keys(next) as ReviewTargetType[]) {
        next[key] = Number(raw[key] ?? 0)
      }
      counts.value = next
    } catch {
      // 计数刷新失败不打断主流程（信号会再触发；顶栏有手动刷新）
      return
    }
    if (backlogTotal.value > 0) {
      try {
        const head = await fetchOldestQueueItem()
        const enteredAt = head.list[0]?.enteredAt
        oldestEnteredAt.value = enteredAt ? Date.parse(enteredAt) : null
      } catch {
        oldestEnteredAt.value = null
      }
    } else {
      oldestEnteredAt.value = null
    }
  }

  function stopLease() {
    if (leaseTimer) {
      clearInterval(leaseTimer)
      leaseTimer = null
    }
  }

  function startLease() {
    stopLease()
    leaseTimer = setInterval(() => {
      if (!current.value) {
        stopLease()
        return
      }
      leaseLeft.value -= 1
      if (leaseLeft.value <= 0) recycle()
    }, 1000)
  }

  function clearCurrent() {
    stopLease()
    current.value = null
    leaseLeft.value = 0
  }

  /** 租约到点（前端倒计时归零；服务端回调并行发生，以刷新计数对齐池中实际） */
  function recycle() {
    const t = current.value
    if (!t) return
    clearCurrent()
    reclaimed.value += 1
    pushEvent(
      'recycle',
      `租约超时 → 工单 #${t.claim.taskId}（${typeMeta(t.claim.targetType).label}）已回收回队列，第 ${reclaimed.value} 次回收`
    )
    toast('租约超时，工单已自动回收回队列', 'warn')
    refresh().then(() => {
      if (backlogTotal.value > 0) firePulse()
    })
  }

  /** 工单已不在我手里（裁决/放回命中 403=已回收、404=不存在）→ 按「已回收」收场 */
  function handleTakenAway(err: unknown, action: string) {
    if (err instanceof ApiError && (err.code === '403' || err.code === '404')) {
      clearCurrent()
      pushEvent('recycle', `${action}失败：工单已被回收（租约过期或他人操作）`)
      toast('工单已被回收，请重新领单', 'warn')
      refresh().then(() => {
        if (backlogTotal.value > 0) firePulse()
      })
      return true
    }
    return false
  }

  // ---------- actions ----------
  /** 派单信号（REVIEW_SIGNAL，载荷 { backlog }）：只唤醒 + 刷新计数条 */
  function onSignal(payload: { backlog?: number }) {
    firePulse()
    const total = typeof payload?.backlog === 'number' ? payload.backlog : backlogTotal.value
    pushEvent('signal', `收到派单信号：当前积压 ${total} 条（上线/入池补推）`)
    refresh()
  }

  /** 拉一条：全量 FIFO（类型计数条只读，不挑类型），一次仅一条 */
  async function pullOne() {
    if (wsStatus.value !== 'open') {
      toast('长连接未就绪，请稍候（或刷新页面）', 'warn')
      return
    }
    if (current.value) {
      toast('手中有单，审完可再拉', 'warn')
      return
    }
    let claim: ReviewTaskClaim | null
    try {
      claim = await claimTask()
    } catch {
      return // http 层已 toast
    }
    if (!claim) {
      toast('暂无可领工单（池空或刚被他人领走）', 'info')
      refresh()
      return
    }
    let detail: ReviewTaskDetail
    try {
      detail = await fetchTaskDetail(claim.taskId)
    } catch {
      // 详情拉取失败（极小概率已被回收）：放回再刷新，避免手里挂一张读不到的单
      releaseTask(claim.taskId).catch(() => {})
      refresh()
      return
    }
    current.value = { claim, detail }
    currentStart = Date.now()
    const remain = Math.ceil((Date.parse(claim.leaseExpireAt) - Date.now()) / 1000)
    // 租约时长以服务端 leaseExpireAt 为唯一锚点（不设本地上限：LEASE_MINUTES 若调大仍正确；
    // 本机时钟与服务端的偏移会直接体现在倒计时里，属已知取舍，权威回收在服务端）
    leaseTotal.value = Math.max(1, remain)
    leaseLeft.value = leaseTotal.value
    startLease()
    pushEvent(
      'claim',
      `拉取工单 #${claim.taskId}（${typeMeta(claim.targetType).label}）· FIFO 最早等待 ${formatWait(
        Date.now() - Date.parse(claim.enteredAt)
      )} · 租约 ${formatWait(leaseTotal.value * 1000)}`
    )
    toast('已拉取一条工单，租约开始计时', 'ok')
    refresh()
  }

  async function decide(approve: boolean, reasonCode?: RejectReasonCode, supplement?: string) {
    const t = current.value
    if (!t) return
    if (!approve && !reasonCode) {
      toast('不通过必须点选一条理由', 'warn')
      return
    }
    const taskId = t.claim.taskId
    const targetType = t.claim.targetType
    const seconds = Math.max(1, Math.round((Date.now() - currentStart) / 1000))
    try {
      await decideTask(taskId, approve, approve ? undefined : reasonCode, approve ? undefined : supplement)
    } catch (err) {
      if (handleTakenAway(err, approve ? '裁决' : '不通过')) return
      return // 其余错误 http 层已 toast，工单仍在手中可重试
    }
    decisions.value.unshift({
      taskId,
      targetType,
      action: approve ? 'PASS' : 'REJECT',
      reasonCode: approve ? undefined : reasonCode,
      supplement: approve ? undefined : supplement?.trim() || undefined,
      seconds,
      at: Date.now()
    })
    clearCurrent()
    if (approve) {
      pushEvent('pass', `裁决通过 → #${taskId}（${typeMeta(targetType).label}）· 耗时 ${seconds}s`)
      toast('已通过，内容对外可见', 'ok')
    } else {
      pushEvent(
        'reject',
        `裁决不通过 → #${taskId}（${typeMeta(targetType).label}）· 理由「${
          reasonCode ? reasonLabel(reasonCode) : ''
        }」· 耗时 ${seconds}s`
      )
      toast('已拒绝，作者将收到通知', 'ok')
    }
    refresh().then(() => {
      // 审完可再拉：仍有积压则重新给出唤醒信号
      if (backlogTotal.value > 0) firePulse()
    })
  }

  /** 主动放回（审不了 → CLAIMED→PENDING） */
  async function release() {
    const t = current.value
    if (!t) return
    const taskId = t.claim.taskId
    try {
      await releaseTask(taskId)
    } catch (err) {
      if (handleTakenAway(err, '放回')) return
      return
    }
    clearCurrent()
    pushEvent('release', `工单 #${taskId}（${typeMeta(t.claim.targetType).label}）已放回队列`)
    toast('已放回，可再拉下一条', 'ok')
    refresh().then(() => {
      if (backlogTotal.value > 0) firePulse()
    })
  }

  function bindWs() {
    wsStatus.value = reviewWs.getStatus()
    reviewWs.onStatus((s) => (wsStatus.value = s))
    reviewWs.on('REVIEW_SIGNAL', (env) => onSignal((env.payload ?? {}) as { backlog?: number }))
  }

  async function init() {
    bindWs()
    await refresh()
    // 连接建立即收到服务端「上线补推」信号帧（含积压数），无需本地重复推
    reviewWs.connect()
    pushEvent('presence', '进入审核中台，长连接建立即在线（presence 落服务端）')
  }

  return {
    // state
    wsStatus,
    counts,
    oldestEnteredAt,
    current,
    leaseTotal,
    leaseLeft,
    decisions,
    reclaimed,
    events,
    pulse,
    // getters
    backlogTotal,
    doneCount,
    reclaimedCount,
    avgSeconds,
    bannerMode,
    leaseRatio,
    // actions
    init,
    refresh,
    pullOne,
    decide,
    release
  }
})
