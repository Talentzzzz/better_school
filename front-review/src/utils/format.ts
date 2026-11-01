/** 等待时长：ms → 「刚刚 / N 分 M 秒 / N 分」 */
export function formatWait(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  if (s < 10) return '刚刚'
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  if (m < 60) {
    const r = s % 60
    return r ? `${m} 分 ${r} 秒` : `${m} 分`
  }
  const h = Math.floor(m / 60)
  return `${h} 小时 ${m % 60} 分`
}

/** 倒计时：秒 → 「MM:SS」 */
export function formatClock(totalSec: number): string {
  const s = Math.max(0, Math.ceil(totalSec))
  const m = Math.floor(s / 60)
  const r = s % 60
  return `${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}`
}

/** 时间戳 → 「HH:MM:SS」 */
export function formatClockTime(ts: number): string {
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 金额：分/元 数值 → 「12.00」 */
export function formatMoney(n: number): string {
  return n.toFixed(2)
}
