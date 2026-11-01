import { ref } from 'vue'

interface ConfirmState {
  open: boolean
  title: string
  message: string
  resolve: ((v: boolean) => void) | null
}

const state = ref<ConfirmState>({ open: false, title: '', message: '', resolve: null })

export function confirmDialog(title: string, message = ''): Promise<boolean> {
  return new Promise((resolve) => {
    state.value = { open: true, title, message, resolve }
  })
}

export function useConfirm() {
  return state
}
