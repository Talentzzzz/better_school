import { ref } from 'vue'

export interface ToastItem {
  id: number
  text: string
}

const toasts = ref<ToastItem[]>([])
let seq = 0

export function toast(text: string) {
  const id = ++seq
  toasts.value.push({ id, text })
  setTimeout(() => {
    const i = toasts.value.findIndex((t) => t.id === id)
    if (i >= 0) toasts.value.splice(i, 1)
  }, 2200)
}

export function useToasts() {
  return toasts
}
