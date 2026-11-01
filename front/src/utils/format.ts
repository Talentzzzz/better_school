export function formatTime(iso: string | null | undefined): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  const now = new Date()
  const diff = now.getTime() - date.getTime()
  const minutes = Math.floor(diff / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes}分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}小时前`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days}天前`
  const sameYear = date.getFullYear() === now.getFullYear()
  const mm = String(date.getMonth() + 1).padStart(2, '0')
  const dd = String(date.getDate()).padStart(2, '0')
  if (sameYear) return `${mm}-${dd}`
  return `${date.getFullYear()}-${mm}-${dd}`
}

export function formatCount(n: number): string {
  if (n >= 10000) {
    const w = Math.floor(n / 1000) / 10
    return `${w}万`
  }
  return String(n)
}

export function formatMoney(n: number | null | undefined): string {
  if (n == null || Number.isNaN(n)) return '0'
  return Number.isInteger(n) ? String(n) : n.toFixed(2)
}

/** 精确到分的绝对时间（formatTime 是相对时间，不适合「中奖于 …」这类留档时点） */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
