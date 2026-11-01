import { ref } from 'vue'

// 轻量 toast 通道：http 拦截器 / ws 客户端 / store 共用（与 front/src/components/Toast.ts 同款形态）。
export interface ToastItem {
  id: number
  text: string
  tone: 'ok' | 'warn' | 'info'
}

const items = ref<ToastItem[]>([])
let seq = 0

export function toast(text: string, tone: 'ok' | 'warn' | 'info' = 'info') {
  const id = ++seq
  items.value.push({ id, text, tone })
  setTimeout(() => {
    const i = items.value.findIndex((t) => t.id === id)
    if (i >= 0) items.value.splice(i, 1)
  }, 3200)
}

export function useToasts() {
  return items
}
